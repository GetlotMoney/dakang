package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONUtil;
import com.jbk.serve.mapper.mall.WsMallFulfillmentMapper;
import com.jbk.serve.mapper.mall.WsMallLogisticsOutboxMapper;
import com.jbk.serve.service.mall.ILogisticsProviderAdapter;
import com.jbk.serve.service.mall.IMallLogisticsService;
import com.jbk.serve.service.mall.IMallShipmentService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.mall.bo.MallShipmentCreateBo;
import com.jbk.tool.data.mall.po.WsMallFulfillment;
import com.jbk.tool.data.mall.po.WsMallLogisticsOutbox;
import com.jbk.tool.data.mall.po.WsMallShipment;
import com.jbk.tool.data.mall.vo.MallFulfillVo;
import com.jbk.tool.data.mall.vo.MallLogisticsProviderVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 第三方物流渠道服务实现（E2E-09 L1）。事务只写 outbox：外部请求不得发生在数据库事务内，
 * 事务里只做「冻结渠道→建包裹→登记动作→推进履约」四件本地事，外呼由 Worker 领取后执行。
 * 履约先推进到「待承运方揽收」而包裹停「待发运」——平台已安排、承运方尚未受理，两个状态各自诚实。
 *
 * @author dakang
 * @since 2026-08-11
 */
@Slf4j
@Service
public class MallLogisticsServiceImpl implements IMallLogisticsService {

    @Autowired
    private MallFulfillCore core;
    @Autowired
    private WsMallFulfillmentMapper fulfillMapper;
    @Autowired
    private WsMallLogisticsOutboxMapper outboxMapper;
    @Autowired
    private IMallShipmentService shipmentService;
    @Autowired
    private IWsMessageService messageService;
    /** 适配器按开关条件装配：取不到即拒绝建单，绝不降级成「假装发出去了」。 */
    @Autowired
    private ObjectProvider<ILogisticsProviderAdapter> adapters;

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallFulfillVo createShipment(Long operatorId, MallShipmentCreateBo bo) {
        WsMallFulfillment task = core.requireTask(bo.getOrderNo());
        core.requireWarehouseOperator(operatorId, task);
        core.requireFulfillingOrder(task);
        requireArrangeable(task);
        requireRegisteredProvider(bo.getProviderCode());
        String now = DateUtils.time();

        // 渠道冻结先于一切：与自营分配的 CAS 0→1 互斥，影响 0 行即已被另一条链接走
        freezeThirdPartyMode(task, operatorId, now);
        WsMallShipment shipment = shipmentService.ensureShipment(task,
                MallEnum.ShipmentDirection.FORWARD.getValue(), 1, null,
                bo.getProviderCode(), operatorId, now);
        registerCreateAction(shipment, operatorId, now);

        // 履约推进到「待承运方揽收」：重复点击时 CAS 影响 0 行，按已安排处理而不是报错
        if (ObjectUtil.equal(task.getFulfillStatus(),
                MallEnum.FulfillStatus.PENDING_ASSIGN.getValue())) {
            core.advance(task, MallEnum.FulfillStatus.PENDING_ASSIGN,
                    MallEnum.FulfillStatus.PENDING_FETCH, "ASSIGN_TIME", operatorId, now);
            core.writeTrace(task, MallEnum.FulfillStatus.PENDING_FETCH,
                    MallEnum.ActorType.WAREHOUSE, operatorId, null, now,
                    "已提交第三方运单请求（承运商 " + bo.getProviderCode() + "）");
            messageService.sendInApp(task.getUserId(), MessageEnum.MsgDomain.MALL,
                    "商城订单已安排发运",
                    "您的商城订单 " + task.getOrderNo() + " 已交由物流承运，运单号稍后可查。",
                    "mallOrder", task.getOrderNo(), now);
            core.writeAudit(task, MallEnum.FulfillStatus.PENDING_FETCH,
                    OpsEnum.ActorPortal.MANAGE, operatorId,
                    "创建第三方运单请求，承运商 " + bo.getProviderCode());
        }
        return core.toVoChecked(core.requireTask(task.getOrderNo()), true);
    }

    @Override
    public List<MallLogisticsProviderVo> providers() {
        List<MallLogisticsProviderVo> list = new ArrayList<>();
        for (ILogisticsProviderAdapter adapter : adapters) {
            if (MallShipmentGate.SELF_PROVIDER.equals(adapter.providerCode())) {
                // 自营不是第三方承运商，不进这个下拉
                continue;
            }
            list.add(new MallLogisticsProviderVo()
                    .setProviderCode(adapter.providerCode())
                    .setProviderName(adapter.providerName()));
        }
        list.sort(Comparator.comparing(MallLogisticsProviderVo::getProviderCode));
        return list;
    }

    /**
     * 只有「待安排发运」的任务能创建运单（已在待承运方揽收视为重复提交，交给下游幂等）。
     *
     * <p>此前这里没有任何履约状态前置，只有推进那一步写了句 {@code if (状态 == 3)}。
     * 于是拣完货还没打包的单也能被交给承运商：渠道被冻结、包裹与 outbox 全建出，
     * 而那句 if 判不中，接口照常返回成功。等承运方事件回来，推进段按「小于就推」
     * 把履约从 2 待打包 一路顶到 4 甚至 5，打包节点凭空消失且 PACK_TIME 永远为空；
     * 仓库操作员此后点「打包完成」，CAS 前态 2 恒影响 0 行——这一单再也打不了包。</p>
     *
     * <p>把「能不能做」和「做完推到哪」分成两处判据是这类洞的共同成因：
     * 前者缺席时，后者那句 if 只会安静地跳过，不会拒绝。</p>
     */
    private void requireArrangeable(WsMallFulfillment task) {
        boolean arrangeable = ObjectUtil.equal(task.getFulfillStatus(),
                MallEnum.FulfillStatus.PENDING_ASSIGN.getValue())
                || ObjectUtil.equal(task.getFulfillStatus(),
                        MallEnum.FulfillStatus.PENDING_FETCH.getValue());
        if (!arrangeable) {
            throw new JbkException("当前履约状态不能创建运单（需先完成拣货与打包）");
        }
    }

    /**
     * 承运商必须是已注册的适配器。
     *
     * <p>不校验的话，运营填一个不存在的编码也能建出包裹与 outbox，
     * 然后 Worker 永远取不到适配器——一张永远发不出去的运单，而界面上它看着已经安排好了。</p>
     */
    private void requireRegisteredProvider(String providerCode) {
        List<ILogisticsProviderAdapter> all = adapters.stream().toList();
        if (all.isEmpty()) {
            throw new JbkException("未启用任何承运商适配器，无法创建第三方运单");
        }
        boolean known = all.stream().anyMatch(a -> a.providerCode().equals(providerCode));
        if (!known) {
            throw new JbkException("承运商未接入：" + providerCode);
        }
        if (MallShipmentGate.SELF_PROVIDER.equals(providerCode)) {
            throw new JbkException("自营承运不走第三方运单入口");
        }
    }

    /** 冻结渠道为第三方；已是第三方视为重复提交，已是自营则拒绝。 */
    private void freezeThirdPartyMode(WsMallFulfillment task, Long operatorId, String now) {
        if (ObjectUtil.equal(task.getFulfillMode(),
                MallEnum.FulfillMode.THIRD_PARTY.getValue())) {
            return;
        }
        if (ObjectUtil.equal(task.getFulfillMode(),
                MallEnum.FulfillMode.SELF_DELIVERY.getValue())) {
            throw new JbkException("该订单已分配自营配送员，不能再创建第三方运单");
        }
        int frozen = fulfillMapper.casFreezeMode(task.getId(),
                MallEnum.FulfillMode.THIRD_PARTY.getValue(), operatorId, now);
        if (frozen != 1) {
            throw new JbkException("承运渠道已被占用（可能已分配自营配送员），请刷新后重试");
        }
        task.setFulfillMode(MallEnum.FulfillMode.THIRD_PARTY.getValue());
        // 与自营同理：冻结也 VERSION+1，内存副本不同步则后续状态 CAS 必然撞空
        task.setVersion(task.getVersion() + 1);
    }

    /**
     * 登记出站动作：同一包裹的同一动作只登记一次。
     *
     * <p>请求快照在登记这一刻冻结，Worker 只按快照调用、不回读当前业务态——
     * 回读会让「登记时说好要发的东西」和「慢重试时真正发出去的东西」悄悄分叉。</p>
     */
    private void registerCreateAction(WsMallShipment shipment, Long operatorId, String now) {
        String key = MallShipmentGate.actionKey(shipment.getId(),
                MallEnum.LogisticsAction.CREATE_ORDER.getValue());
        if (ObjectUtil.isNotNull(outboxMapper.selectByKeyIncludingDeleted(key))) {
            return;
        }
        String snap = JSONUtil.createObj()
                .set("orderNo", shipment.getOrderNo())
                .set("shipmentId", shipment.getId())
                .set("providerCode", shipment.getProviderCode())
                .set("direction", shipment.getDirection())
                .toString();
        WsMallLogisticsOutbox action = new WsMallLogisticsOutbox()
                .setShipmentId(shipment.getId())
                .setActionType(MallEnum.LogisticsAction.CREATE_ORDER.getValue())
                .setBizActionKey(key)
                .setRequestSnap(snap)
                .setProcessingStatus(MallEnum.LogisticsProcessing.PENDING.getValue())
                .setRetryCount(0);
        action.setCreateTime(now);
        action.setUpdateTime(now);
        action.setCreateBy(operatorId);
        action.setUpdateBy(operatorId);
        try {
            outboxMapper.insert(action);
        }
        catch (DuplicateKeyException race) {
            // 并发登记撞唯一键：已有一条等价动作，本次不重复登记
            log.debug("出站动作已登记，跳过 key={}", key);
        }
    }
}
