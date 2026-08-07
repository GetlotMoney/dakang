package com.jbk.serve.service.device.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.device.DeviceAvailabilityGuard;
import com.jbk.serve.service.device.IDispenseDispatchTxService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.ITradeOrderTxService;
import com.jbk.serve.service.trade.WaterOrderSnapshot;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出水指令准备事务实现。
 *
 * <p>锁序与取水下单事务同前缀，全仓一致：设备(X) → 出水口(X) → 故障字典(S) → 水站(S) → 订单(X)。
 * 订单虽然是本事务的入口对象，也必须排在设备之后取锁——下单事务持有设备锁后才写订单，
 * 反过来先锁订单再锁设备就会成环。为此先做一次<b>不加锁</b>的订单读，仅用于定位该锁哪几行，
 * 全部校验一律基于随后取得的锁内值。</p>
 *
 * @author dakang
 * @since 2026-08-03
 */
@Service
@RequiredArgsConstructor
public class DispenseDispatchTxServiceImpl implements IDispenseDispatchTxService {

    private final WsCommandMapper commandMapper;
    private final WsOrderMapper orderMapper;
    private final WsStationMapper stationMapper;
    private final DeviceAvailabilityGuard availabilityGuard;
    private final ITradeOrderTxService tradeOrderTxService;
    private final IWsDomainEventService domainEventService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Prepared prepare(Long orderId) {
        // ① 无锁定位读：只用来知道该锁哪台设备、哪个出水口，不参与任何判定
        WsOrder routing = orderMapper.selectById(orderId);
        if (ObjectUtil.isNull(routing)) {
            throw new JbkException("订单不存在");
        }
        if (ObjectUtil.isNotNull(routing.getCmdId())) {
            // 快速幂等：已绑定指令，无需进入取锁流程（锁内还会再核一次）
            return new Prepared(routing.getCmdId(), false, null, null);
        }
        if (ObjectUtil.isNull(routing.getDeviceId()) || ObjectUtil.isNull(routing.getOutletId())) {
            throw new JbkException("订单缺少设备或出水口，无法下发出水指令");
        }

        // ② 按全仓锁序取权威当前数据
        DeviceAvailabilityGuard.CheckedDeviceContext checked =
                availabilityGuard.loadForUpdate(routing.getDeviceId(), routing.getOutletId());
        if (checked.archiveMissing()) {
            throw new JbkException("设备或出水口档案缺失，不下发出水指令");
        }
        WsDevice device = checked.device();
        WsDeviceOutlet outlet = checked.outlet();
        WsStation station = stationMapper.selectByIdForShare(device.getStationId());
        if (ObjectUtil.isNull(station)) {
            throw new JbkException("设备所属水站档案缺失，不下发出水指令");
        }
        // 与下单事务同口径：水站停用是显式阻断项。扣款提交后运营停站的窗口就在这里，
        // 只比 ID 不看状态会向一个已停业的站继续下发出水。
        if (ObjectUtil.notEqual(station.getStationStatus(), 1)) {
            throw new JbkException("水站已停用，不下发出水指令");
        }

        // ③ 订单当前读并锁定；此后所有判定只认锁内订单
        WsOrder order = orderMapper.selectByIdForUpdate(orderId);
        if (ObjectUtil.isNull(order)) {
            throw new JbkException("订单不存在或已删除");
        }
        if (ObjectUtil.isNotNull(order.getCmdId())) {
            return new Prepared(order.getCmdId(), false, null, null);
        }
        if (ObjectUtil.notEqual(order.getOrderType(), TradeEnum.OrderType.WATER.getValue())) {
            throw new JbkException("非取水订单不下发出水指令");
        }
        if (ObjectUtil.notEqual(order.getOrderStatus(), TradeEnum.OrderStatus.PAID.getValue())) {
            throw new JbkException("订单状态不支持下发出水指令");
        }
        boolean aligned = ObjectUtil.equal(order.getStationId(), station.getId())
                && ObjectUtil.equal(device.getStationId(), station.getId())
                && ObjectUtil.equal(order.getDeviceId(), device.getId())
                && ObjectUtil.equal(order.getOutletId(), outlet.getId())
                && ObjectUtil.equal(outlet.getDeviceId(), device.getId());
        if (!aligned) {
            throw new JbkException("订单与设备/出水口档案共键已变化，不下发出水指令");
        }
        // 冻结快照：用户认可的是「那种水、那个量、那个价」。同一个口改了水种就不是同一笔交易；
        // 水量/支付方式/金额三维也一并复核，与下单事务保持同一口径（纵深防御，不依赖上游不改数据）。
        WaterOrderSnapshot.Frozen frozen;
        try {
            // 用幂等口径解析：水种冻结上线前的历史快照没有 waterTypeId，硬拒会把在途老单批量推进 6
            frozen = WaterOrderSnapshot.parseForIdempotency(order.getPackageSnap());
        }
        catch (JbkException invalid) {
            throw new JbkException("订单下单快照不合法，不下发出水指令（" + invalid.getMsg() + "）");
        }
        if (ObjectUtil.notEqual(order.getPlanMl(), frozen.planMl())
                || ObjectUtil.notEqual(order.getPayWay(), frozen.payWay())
                || ObjectUtil.notEqual(order.getOrderAmount(), WaterOrderSnapshot.expectedOrderAmount(frozen))) {
            throw new JbkException("订单水量/支付方式/金额与下单快照不一致，不下发出水指令");
        }
        if (ObjectUtil.isNull(frozen.waterTypeId())) {
            // 历史快照无冻结水种：无从比对，按既有兼容口径放行并留痕，不把老单判死
            domainEventService.record(OpsEnum.EventType.ORDER_STATUS, order.getOrderNo(), null,
                    "下单快照无冻结水种（上线前历史单），本次下发跳过水种核对");
        }
        else if (ObjectUtil.notEqual(frozen.waterTypeId(), outlet.getWaterTypeId())) {
            throw new JbkException("出水口水种已变更，不下发出水指令");
        }
        if (!checked.available()) {
            throw new JbkException("设备当前不可用，不下发出水指令："
                    + StrUtil.blankToDefault(checked.reason(), checked.verdict().code()));
        }

        // ④ 落 PENDING 指令；报文与下发目标全部取自本次当前读
        JSONObject payload = new JSONObject();
        payload.set("outletNo", outlet.getOutletNo());
        payload.set("waterTypeId", outlet.getWaterTypeId());
        payload.set("waterType", outlet.getWaterType());
        payload.set("planMl", order.getPlanMl());
        payload.set("orderNo", order.getOrderNo());
        WsCommand command = new WsCommand()
                .setCmdNo("CMD" + DateUtils.time() + RandomUtil.randomNumbers(6))
                .setDeviceId(device.getId())
                .setOrderId(orderId)
                .setCmdType(DeviceEnum.CmdType.START_DISPENSE.getValue())
                .setCmdPayload(payload.toString())
                .setCmdStatus(DeviceEnum.CmdStatus.PENDING.getValue())
                .setRetryCount(0);
        commandMapper.insert(command);

        // ⑤ 同事务抢占 CMD_ID：CAS 复核类型/状态/三共键。影响行数不是 1 就抛出——
        //    事务回滚会把④刚插的指令一并撤销，不留作废垃圾行。
        int won = tradeOrderTxService.claimCommandSlot(orderId, command.getId(),
                station.getId(), device.getId(), outlet.getId());
        if (won != 1) {
            throw new JbkException("订单状态或设备共键已变化，不下发出水指令");
        }
        domainEventService.record(OpsEnum.EventType.COMMAND_STATUS, command.getCmdNo(),
                null, DeviceEnum.CmdStatus.PENDING.getDesc());
        return new Prepared(command.getId(), true, command, device.getDeviceNo());
    }
}
