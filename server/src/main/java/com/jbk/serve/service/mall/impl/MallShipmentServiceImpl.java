package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.mapper.mall.WsMallFulfillmentMapper;
import com.jbk.serve.mapper.mall.WsMallLogisticsEventMapper;
import com.jbk.serve.mapper.mall.WsMallOrderItemMapper;
import com.jbk.serve.mapper.mall.WsMallOrderMapper;
import com.jbk.serve.mapper.mall.WsMallShipmentItemMapper;
import com.jbk.serve.mapper.mall.WsMallShipmentMapper;
import com.jbk.serve.service.mall.IMallShipmentService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.po.WsMallFulfillment;
import com.jbk.tool.data.mall.po.WsMallLogisticsEvent;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.po.WsMallOrderItem;
import com.jbk.tool.data.mall.po.WsMallShipment;
import com.jbk.tool.data.mall.po.WsMallShipmentItem;
import com.jbk.tool.data.mall.vo.MallShipmentVo;
import com.jbk.tool.exception.JbkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 商城出库包裹服务实现（E2E-09 L1）。渠道无关：渠道值一律取自履约总单、不接受入参——
 * 让调用方传渠道等于允许建出与总单渠道分叉且无法修复的包裹。
 *
 * @author dakang
 * @since 2026-08-11
 */
@Slf4j
@Service
public class MallShipmentServiceImpl implements IMallShipmentService {

    @Autowired
    private WsMallShipmentMapper shipmentMapper;
    @Autowired
    private WsMallShipmentItemMapper shipmentItemMapper;
    @Autowired
    private WsMallFulfillmentMapper fulfillMapper;
    @Autowired
    private WsMallOrderMapper orderMapper;
    @Autowired
    private WsMallOrderItemMapper orderItemMapper;
    @Autowired
    private WsMallLogisticsEventMapper eventMapper;

    @Override
    public WsMallShipment ensureShipment(WsMallFulfillment task, int direction, int seq,
                                         Long sourceAfterSaleId, String providerCode,
                                         Long operator, String now) {
        if (!MallEnum.ShipmentDirection.isKnown(direction)) {
            throw new JbkException("包裹方向不合法");
        }
        if (!MallEnum.FulfillMode.isKnown(task.getFulfillMode())
                || ObjectUtil.equal(task.getFulfillMode(),
                        MallEnum.FulfillMode.UNDECIDED.getValue())) {
            // 渠道未冻结就建包裹，等于让包裹先于决定存在——它该由谁承运没有答案
            throw new JbkException("履约渠道尚未冻结，不能创建包裹");
        }
        String key = MallShipmentGate.shipmentKey(task.getOrderNo(), direction, seq);
        WsMallShipment existed = shipmentMapper.selectByKeyIncludingDeleted(key);
        if (ObjectUtil.isNotNull(existed)) {
            requireSameShipment(existed, task, direction, seq);
            return existed;
        }
        WsMallShipment shipment = new WsMallShipment()
                .setFulfillId(task.getId())
                .setOrderId(task.getOrderId())
                .setOrderNo(task.getOrderNo())
                .setSourceAfterSaleId(sourceAfterSaleId)
                .setDirection(direction)
                .setShipmentSeq(seq)
                .setFulfillMode(task.getFulfillMode())
                .setProviderCode(providerCode)
                .setShipmentStatus(MallEnum.ShipmentStatus.PENDING.getValue())
                .setVersion(1)
                .setCreateShipTime(now)
                .setBizIdempotencyKey(key);
        shipment.setCreateTime(now);
        shipment.setUpdateTime(now);
        shipment.setCreateBy(operator);
        shipment.setUpdateBy(operator);
        try {
            shipmentMapper.insert(shipment);
        }
        catch (DuplicateKeyException race) {
            // 并发建包裹撞 uk(BIZ_IDEMPOTENCY_KEY)：一方赢，输方重读赢家并核对后原样返回
            WsMallShipment winner = shipmentMapper.selectByKeyIncludingDeleted(key);
            if (ObjectUtil.isNull(winner)) {
                throw new JbkException("包裹创建冲突，请重试");
            }
            requireSameShipment(winner, task, direction, seq);
            return winner;
        }
        writeItems(shipment, operator, now);
        return shipment;
    }

    /**
     * 同键复用时的正文一致性：不可变内容必须相同。
     *
     * <p>同一把幂等键对应同一个包裹。键相同而归属或方向不同，只可能是伪造或数据错位，
     * 静默返回等于替它盖章。</p>
     */
    private void requireSameShipment(WsMallShipment existed, WsMallFulfillment task,
                                     int direction, int seq) {
        boolean same = ObjectUtil.equal(existed.getFulfillId(), task.getId())
                && ObjectUtil.equal(existed.getOrderNo(), task.getOrderNo())
                && ObjectUtil.equal(existed.getDirection(), direction)
                && ObjectUtil.equal(existed.getShipmentSeq(), seq);
        if (!same) {
            throw new JbkException("同一包裹幂等键对应了不一致的归属，已拒绝（请人工核查）");
        }
    }

    /**
     * 正向包裹按订单明细全量展开。
     *
     * <p>一期一单一包裹，故不按件拆分；表结构与唯一键已支持多包裹，
     * 但**不偷偷实现部分发货**——那需要与库存出库口径一起设计，不是加个循环的事。</p>
     */
    private void writeItems(WsMallShipment shipment, Long operator, String now) {
        List<WsMallOrderItem> items = orderItemMapper.selectByOrderIdOrderBySku(shipment.getOrderId());
        if (items.isEmpty()) {
            throw new JbkException("订单无明细，无法创建包裹");
        }
        for (WsMallOrderItem item : items) {
            WsMallShipmentItem line = new WsMallShipmentItem()
                    .setShipmentId(shipment.getId())
                    .setOrderItemId(item.getId())
                    // 正向发货恒 0 而不是 null：唯一键含它，null 之间互不冲突
                    .setAfterSaleItemId(0L)
                    .setSkuId(item.getSkuId())
                    .setQuantity(item.getQuantity());
            line.setCreateTime(now);
            line.setUpdateTime(now);
            line.setCreateBy(operator);
            line.setUpdateBy(operator);
            try {
                shipmentItemMapper.insert(line);
            }
            catch (DuplicateKeyException race) {
                // 并发重复展开：唯一键兜住，本条已存在即跳过
                log.debug("包裹明细已存在，跳过 shipmentId={} orderItemId={}",
                        shipment.getId(), item.getId());
            }
        }
    }

    @Override
    public Advance advance(WsMallShipment shipment, int toStatus, String timeColumn,
                           Long operator, String now) {
        if (!MallEnum.ShipmentStatus.isKnown(toStatus)) {
            throw new JbkException("包裹目标状态不合法");
        }
        if (!MallShipmentGate.isForward(shipment.getShipmentStatus(), toStatus)) {
            // 迟到与乱序是物流事件的常态：只判「不等于就更新」会让已送达被推回运输中
            return Advance.STALE;
        }
        int moved = shipmentMapper.casStatus(shipment.getId(), shipment.getShipmentStatus(),
                toStatus, shipment.getVersion(), timeColumn, operator, now);
        if (moved == 1) {
            shipment.setShipmentStatus(toStatus).setVersion(shipment.getVersion() + 1);
            return Advance.MOVED;
        }
        // 影响 0 行有两种解释，处置完全相反，必须回读区分：
        // 另一条并发事实先提交把 VERSION 顶走了（本条一步没生效，要重投），
        // 还是这条本就是重放（包裹已在同态或更后，如实记为已处理）。
        // 混成一个 false 的代价很具体：一条真实的「已送达」被标成已处理后，
        // 包裹停在已揽收、履约停在运输中、用户的确认收货按钮永远不出现，
        // 而重投扫描面再也看不到它。
        WsMallShipment fresh = shipmentMapper.selectById(shipment.getId());
        if (ObjectUtil.isNull(fresh) || !ObjectUtil.equal(fresh.getDataStatus(), 0)) {
            return Advance.STALE;
        }
        return MallShipmentGate.isForward(fresh.getShipmentStatus(), toStatus)
                ? Advance.LOST
                : Advance.STALE;
    }

    @Override
    public Advance advanceById(Long shipmentId, int toStatus, String timeColumn, Long operator,
                               String now) {
        WsMallShipment shipment = shipmentMapper.selectById(shipmentId);
        if (ObjectUtil.isNull(shipment)) {
            throw new JbkException("包裹不存在");
        }
        return advance(shipment, toStatus, timeColumn, operator, now);
    }

    @Override
    public List<MallShipmentVo> listByOrderNo(String orderNo) {
        List<MallShipmentVo> result = new ArrayList<>();
        for (WsMallShipment shipment : shipmentMapper.selectByOrderNo(orderNo)) {
            if (!ObjectUtil.equal(shipment.getDataStatus(), 0)) {
                continue;
            }
            // 组装前必过共键：未经校验的包裹不得进入任何 Vo（与 S3 履约 toVoChecked 同口径）
            WsMallFulfillment task = fulfillMapper.selectById(shipment.getFulfillId());
            WsMallOrder order = orderMapper.selectByOrderNoIncludingDeleted(shipment.getOrderNo());
            String miss = MallShipmentGate.linkMismatch(shipment, task, order);
            if (miss != null) {
                throw new JbkException("包裹证据异常（" + miss + "），请人工核查");
            }
            result.add(toVo(shipment));
        }
        return result;
    }

    @Override
    public List<MallShipmentVo> listForUser(Long userId, String orderNo) {
        WsMallOrder order = orderMapper.selectByOrderNoIncludingDeleted(orderNo);
        if (ObjectUtil.isNull(order) || !ObjectUtil.equal(order.getDataStatus(), 0)
                || !ObjectUtil.equal(order.getUserId(), userId)) {
            // 不区分「不存在」与「不归你」：区分开就等于把别人的订单号是否存在告诉了调用方
            throw new JbkException("订单不存在");
        }
        return listByOrderNo(orderNo);
    }

    private MallShipmentVo toVo(WsMallShipment shipment) {
        MallShipmentVo vo = new MallShipmentVo()
                .setShipmentId(String.valueOf(shipment.getId()))
                .setOrderNo(shipment.getOrderNo())
                .setDirection(shipment.getDirection())
                .setDirectionName(directionName(shipment.getDirection()))
                .setFulfillMode(shipment.getFulfillMode())
                .setFulfillModeName(modeName(shipment.getFulfillMode()))
                .setProviderCode(shipment.getProviderCode())
                .setServiceCode(shipment.getServiceCode())
                .setWaybillNo(shipment.getWaybillNo())
                .setShipmentStatus(shipment.getShipmentStatus())
                .setShipmentStatusName(shipmentStatusName(shipment.getShipmentStatus()))
                .setCreateShipTime(shipment.getCreateShipTime())
                .setPickupTime(shipment.getPickupTime())
                .setDeliverTime(shipment.getDeliverTime());
        List<MallShipmentVo.Line> lines = new ArrayList<>();
        for (WsMallShipmentItem item : shipmentItemMapper
                .selectByShipmentOrderBySku(shipment.getId())) {
            lines.add(new MallShipmentVo.Line()
                    .setOrderItemId(String.valueOf(item.getOrderItemId()))
                    .setSkuId(String.valueOf(item.getSkuId()))
                    .setQuantity(item.getQuantity()));
        }
        vo.setLines(lines);
        List<MallShipmentVo.TraceNode> traces = new ArrayList<>();
        if (ObjectUtil.equal(shipment.getFulfillMode(),
                MallEnum.FulfillMode.THIRD_PARTY.getValue())) {
            for (WsMallLogisticsEvent event : eventMapper.selectTimeline(shipment.getId())) {
                traces.add(new MallShipmentVo.TraceNode()
                        .setEventState(event.getEventState())
                        .setEventStateName(eventStateName(event.getEventState()))
                        .setEventTime(event.getEventTime())
                        .setEventDesc(event.getEventDesc()));
            }
        }
        vo.setLogisticsTraces(traces);
        return vo;
    }

    private static String directionName(Integer value) {
        for (MallEnum.ShipmentDirection item : MallEnum.ShipmentDirection.values()) {
            if (ObjectUtil.equal(item.getValue(), value)) {
                return item.getDesc();
            }
        }
        return String.valueOf(value);
    }

    private static String modeName(Integer value) {
        for (MallEnum.FulfillMode item : MallEnum.FulfillMode.values()) {
            if (ObjectUtil.equal(item.getValue(), value)) {
                return item.getDesc();
            }
        }
        return String.valueOf(value);
    }

    private static String shipmentStatusName(Integer value) {
        for (MallEnum.ShipmentStatus item : MallEnum.ShipmentStatus.values()) {
            if (ObjectUtil.equal(item.getValue(), value)) {
                return item.getDesc();
            }
        }
        return String.valueOf(value);
    }

    /** 事件状态名：白名单外原样回显，不编造中文——编造会让排障看不出是未知状态。 */
    private static String eventStateName(String state) {
        return switch (state == null ? "" : state) {
            case MallEnum.LogisticsEventState.CREATED -> "已接单";
            case MallEnum.LogisticsEventState.PICKED_UP -> "已揽收";
            case MallEnum.LogisticsEventState.IN_TRANSIT -> "运输中";
            case MallEnum.LogisticsEventState.DELIVERED -> "已送达";
            case MallEnum.LogisticsEventState.SIGNED -> "已签收";
            case MallEnum.LogisticsEventState.EXCEPTION -> "异常";
            case MallEnum.LogisticsEventState.CANCELLED -> "已取消";
            default -> String.valueOf(state);
        };
    }
}
