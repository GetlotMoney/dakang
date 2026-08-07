package com.jbk.serve.service.delivery.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryExceptionMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.aftersale.AfterSaleStrategy;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.delivery.DeliveryClock;
import com.jbk.serve.service.delivery.DeliveryLinkGuard;
import com.jbk.serve.service.delivery.DeliveryRefundSnapshot;
import com.jbk.serve.service.delivery.IDeliveryAppealTxService;
import com.jbk.serve.service.delivery.IDeliveryMediaService;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.delivery.bo.DeliveryAppealCreateBo;
import com.jbk.tool.data.delivery.bo.DeliveryAppealDecideBo;
import com.jbk.tool.data.delivery.bo.DeliveryAppealEvidenceBo;
import com.jbk.tool.data.delivery.po.WsDeliveryAppeal;
import com.jbk.tool.data.delivery.po.WsDeliveryException;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 配送申诉服务实现（E2E-03 A4）。
 *
 * <p>活动申诉唯一由 uk_appeal_active_task（生成列唯一键）在数据库层收敛；
 * 裁决/任务状态迁移全部条件 UPDATE 并校验影响行数。属主校验先于共键校验
 * （错位订单按「不存在或无权访问」拒绝，不泄露他人订单存在性——Mock 口径）。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Service
public class DeliveryAppealTxServiceImpl implements IDeliveryAppealTxService {

    @Autowired
    private WsDeliveryAppealMapper appealMapper;
    @Autowired
    private WsDeliveryTaskMapper taskMapper;
    @Autowired
    private WsDeliveryExceptionMapper exceptionMapper;
    @Autowired
    private WsOrderMapper orderMapper;
    @Autowired
    private CourierAccess courierAccess;
    @Autowired
    private IDeliveryMediaService mediaService;
    @Autowired
    private IWsMessageService messageService;
    @Autowired
    private IWsDomainEventService domainEventService;
    /**
     * 售后执行内核（E2E-04 包A）。{@code createPending} 是 {@code REQUIRED} 传播，
     * 会并入本类裁决事务：裁决回滚时那条待执行动作必须一并消失，否则会留下
     * 「申诉没裁决、售后却待执行」的幽灵返还。
     */
    @Autowired
    private IAfterSaleActionTxService afterSaleActionTxService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsDeliveryAppeal createAppeal(DeliveryAppealCreateBo bo, Long actorUserId, String now) {
        DeliveryClock.requireTime(now, "当前时间");
        if (ObjectUtil.isNull(actorUserId) || actorUserId <= 0) {
            throw new JbkException("会话用户非法");
        }
        // 属主先行（规则15）：订单不存在或不属于本人，一律同口径拒绝
        WsOrder order = orderMapper.selectOne(Wrappers.lambdaQuery(WsOrder.class)
                .eq(WsOrder::getOrderNo, StrUtil.trim(bo.getOrderNo())));
        if (ObjectUtil.isNull(order) || ObjectUtil.notEqual(order.getUserId(), actorUserId)) {
            throw new JbkException("订单不存在或无权访问");
        }
        WsDeliveryTask task = taskMapper.selectOne(Wrappers.lambdaQuery(WsDeliveryTask.class)
                .eq(WsDeliveryTask::getTaskNo, StrUtil.trim(bo.getTaskNo())));
        if (ObjectUtil.isNull(task) || ObjectUtil.notEqual(task.getUserId(), actorUserId)) {
            throw new JbkException("配送任务不存在或无权访问");
        }
        // 共键栅栏：orderType=3 + userId 一致 + 任务外键指向该订单（错位 fail-closed，零副作用）
        DeliveryLinkGuard.requireLinked(order, task);
        if (ObjectUtil.notEqual(order.getOrderStatus(), TradeEnum.OrderStatus.FINISHED.getValue())) {
            throw new JbkException("申诉要求关联订单处于已完成状态");
        }
        if (ObjectUtil.notEqual(task.getTaskStatus(), DeliveryEnum.TaskStatus.SIGNED.getValue())
                || StrUtil.isBlank(task.getSignTime())) {
            throw new JbkException("只有已签收订单可以发起申诉");
        }
        // 防御分支：任务被外力改回 5 而活动申诉仍在——先于窗口判定按重复申诉拒绝
        Long activeCount = appealMapper.selectCount(Wrappers.lambdaQuery(WsDeliveryAppeal.class)
                .eq(WsDeliveryAppeal::getTaskId, task.getId())
                .eq(WsDeliveryAppeal::getAppealStatus, DeliveryEnum.AppealStatus.PENDING.getValue()));
        if (activeCount > 0) {
            throw new JbkException("该任务已有待处理申诉");
        }
        String reason = StrUtil.trim(bo.getReason());
        if (!DeliveryEnum.APPEAL_REASON_CODES.contains(reason)) {
            throw new JbkException("申诉原因不合法");
        }
        if (StrUtil.isBlank(bo.getDescription()) || ObjectUtil.isNull(bo.getReceivedCount())
                || bo.getReceivedCount() < 0) {
            throw new JbkException("申诉说明或实收数量不合法");
        }
        // 规则15 窗口双侧：动作时间必须落在 [signTime, 申诉截止]；截止时间以签收事务落定值为权威
        String deadline = StrUtil.isBlank(task.getAppealDeadline())
                ? DeliveryClock.plusHours(task.getSignTime(), 24)
                : task.getAppealDeadline();
        if (now.compareTo(task.getSignTime()) < 0 || now.compareTo(deadline) > 0) {
            throw new JbkException("申诉须在签收后 24 小时内发起");
        }
        // 举证媒体绑定（本人登记 + 申诉举证用途）
        if (ObjectUtil.isNotNull(bo.getEvidenceRefs()) && !bo.getEvidenceRefs().isEmpty()) {
            mediaService.claimForTask(bo.getEvidenceRefs(), task.getId(), actorUserId,
                    DeliveryEnum.MediaPurpose.APPEAL_EVIDENCE, "申诉举证媒体无效或已被占用");
        }
        WsDeliveryAppeal appeal = new WsDeliveryAppeal()
                .setTaskId(task.getId())
                .setOrderId(order.getId())
                .setUserId(actorUserId)
                .setAppealReason(reason)
                .setAppealDesc(bo.getDescription().trim())
                .setReceivedCount(bo.getReceivedCount())
                .setAppealPhotos(ObjectUtil.isNull(bo.getEvidenceRefs()) ? null
                        : JSONUtil.toJsonStr(bo.getEvidenceRefs()))
                .setAppealStatus(DeliveryEnum.AppealStatus.PENDING.getValue());
        appeal.setCreateTime(now);
        appeal.setUpdateTime(now);
        try {
            appealMapper.insert(appeal);
        } catch (DuplicateKeyException e) {
            // 并发重复申诉：uk_appeal_active_task 数据库层收敛
            throw new JbkException("该任务已有待处理申诉");
        }
        // 任务 5→7（版本+1，条件 UPDATE）；0 行=并发变化，整体回滚零残留
        int moved = taskMapper.update(null, Wrappers.lambdaUpdate(WsDeliveryTask.class)
                .set(WsDeliveryTask::getTaskStatus, DeliveryEnum.TaskStatus.APPEALING.getValue())
                .set(WsDeliveryTask::getVersion, task.getVersion() + 1)
                .set(WsDeliveryTask::getUpdateTime, now)
                .eq(WsDeliveryTask::getId, task.getId())
                .eq(WsDeliveryTask::getTaskStatus, DeliveryEnum.TaskStatus.SIGNED.getValue())
                .eq(WsDeliveryTask::getVersion, task.getVersion()));
        if (moved != 1) {
            throw new JbkException("任务状态已变化，请刷新后重试");
        }
        messageService.sendInApp(actorUserId, MessageEnum.MsgDomain.DELIVERY, "配送申诉已登记",
                "您对订单 " + order.getOrderNo() + " 的申诉已登记，运营核验证据后将给出裁决结果。",
                "appeal", String.valueOf(appeal.getId()), now);
        // 申诉登记推动任务 5→7，属关键状态变化：走可靠路径（失败抛出整体回滚，P1-3）；
        // 动作者是订单用户本人，身份走会话推断（USER 正确），幂等键按申诉行唯一。
        domainEventService.recordReliableOnce(OpsEnum.EventType.DELIVERY_NODE, task.getTaskNo(),
                "APPEAL_CREATE:" + appeal.getId(), null,
                "配送申诉已登记：appealId=" + appeal.getId() + "，orderNo=" + order.getOrderNo()
                        + "，reason=" + reason + "，time=" + now);
        return appeal;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsDeliveryAppeal appendCourierEvidence(DeliveryAppealEvidenceBo bo, Long actorUserId, String now) {
        DeliveryClock.requireTime(now, "当前时间");
        CourierAccess.EnabledCourier courier = courierAccess.requireEnabledCourier(actorUserId);
        WsDeliveryTask task = requireTask(bo.getTaskNo());
        if (ObjectUtil.notEqual(task.getCourierId(), courier.courier().getId())) {
            throw new JbkException("只能就本人任务的申诉举证");
        }
        if (ObjectUtil.notEqual(task.getTaskStatus(), DeliveryEnum.TaskStatus.APPEALING.getValue())) {
            throw new JbkException("任务当前不在申诉中，无法追加举证");
        }
        // 申诉发生在已完成订单上：走关联校验而非履约栅栏，订单必须为 4（Mock 口径）
        WsOrder order = DeliveryLinkGuard.requireLinked(orderMapper.selectById(task.getOrderId()), task);
        if (ObjectUtil.notEqual(order.getOrderStatus(), TradeEnum.OrderStatus.FINISHED.getValue())) {
            throw new JbkException("申诉举证要求关联订单处于已完成状态");
        }
        long appealId = decimalId(bo.getAppealId(), "appealId");
        // FOR UPDATE 锁申诉行：追加举证是读-改-写 JSON，并发追加必须在行锁上串行化
        WsDeliveryAppeal appeal = appealMapper.selectOne(Wrappers.lambdaQuery(WsDeliveryAppeal.class)
                .eq(WsDeliveryAppeal::getId, appealId)
                .eq(WsDeliveryAppeal::getTaskId, task.getId())
                .eq(WsDeliveryAppeal::getOrderId, order.getId())
                .last("FOR UPDATE"));
        if (ObjectUtil.isNull(appeal)
                || ObjectUtil.notEqual(appeal.getAppealStatus(), DeliveryEnum.AppealStatus.PENDING.getValue())) {
            throw new JbkException("申诉不存在、共键不一致或已裁决");
        }
        if (StrUtil.isBlank(bo.getDescription())) {
            throw new JbkException("举证说明不能为空");
        }
        JSONArray evidences = StrUtil.isBlank(appeal.getCourierEvidences())
                ? JSONUtil.createArray()
                : JSONUtil.parseArray(appeal.getCourierEvidences());
        // 举证时间不早于签收、申诉创建与上一份举证（统一逻辑时钟）
        String previousTime = evidences.isEmpty() ? null
                : ((JSONObject) evidences.get(evidences.size() - 1)).getStr("time");
        String evidenceTime = DeliveryClock.floor(now, task.getSignTime(), appeal.getCreateTime(), previousTime);
        if (ObjectUtil.isNotNull(bo.getEvidenceRefs()) && !bo.getEvidenceRefs().isEmpty()) {
            mediaService.claimForTask(bo.getEvidenceRefs(), task.getId(), actorUserId,
                    DeliveryEnum.MediaPurpose.APPEAL_EVIDENCE, "申诉举证媒体无效或已被占用");
        }
        evidences.add(JSONUtil.createObj()
                .set("description", bo.getDescription().trim())
                .set("evidenceRefs", ObjectUtil.isNull(bo.getEvidenceRefs()) ? List.of() : bo.getEvidenceRefs())
                .set("time", evidenceTime));
        int updated = appealMapper.update(null, Wrappers.lambdaUpdate(WsDeliveryAppeal.class)
                .set(WsDeliveryAppeal::getCourierEvidences, evidences.toString())
                .set(WsDeliveryAppeal::getUpdateTime, evidenceTime)
                .eq(WsDeliveryAppeal::getId, appeal.getId())
                .eq(WsDeliveryAppeal::getAppealStatus, DeliveryEnum.AppealStatus.PENDING.getValue()));
        if (updated != 1) {
            throw new JbkException("申诉状态已变化，举证失败");
        }
        // 举证不改申诉/任务状态，举证 JSON 本身是权威证据：审计属展示型走 quietly；
        // 动作者是配送员，portal 显式记 COURIER（P1-3，会话推断只能得到 USER）
        domainEventService.recordAs(OpsEnum.ActorPortal.COURIER, actorUserId,
                OpsEnum.EventType.DELIVERY_NODE, task.getTaskNo(), null,
                "配送员追加申诉举证：appealId=" + appeal.getId() + "，time=" + evidenceTime);
        appeal.setCourierEvidences(evidences.toString());
        return appeal;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean decideAppeal(DeliveryAppealDecideBo bo, Long adminUserId, String now) {
        DeliveryClock.requireTime(now, "当前时间");
        long appealId = decimalId(bo.getAppealId(), "appealId");
        // 裁决入参只有策略码一个真相源（E2E-04 R0-2）：白名单与终态派生都在 AfterSaleStrategy，
        // 本类不再枚举 3/5/2——那会让「策略码说补偿、outcome 说驳回」这种自相矛盾却双双通过
        // 校验的裁决成为可表达状态。规则18 依旧成立：资金策略只落 2成立待补偿 + 一条待执行动作，
        // 本事务绝不写卡余额、绝不写钱包流水。
        AfterSaleEnum.StrategyCode strategy = AfterSaleStrategy.requireStrategy(bo.getStrategyCode());
        DeliveryEnum.AppealStatus outcomeEnum = AfterSaleStrategy.deriveOutcome(strategy);
        int outcome = outcomeEnum.getValue();
        if (StrUtil.isBlank(bo.getHandleResult())) {
            throw new JbkException("裁决必须填写处理结果说明");
        }
        WsDeliveryAppeal appeal = appealMapper.selectById(appealId);
        if (ObjectUtil.isNull(appeal)) {
            throw new JbkException("申诉不存在");
        }
        if (ObjectUtil.notEqual(appeal.getAppealStatus(), DeliveryEnum.AppealStatus.PENDING.getValue())) {
            throw new JbkException("申诉已裁决，不能重复处理");
        }
        WsDeliveryTask task = taskMapper.selectById(appeal.getTaskId());
        if (ObjectUtil.isNull(task) || ObjectUtil.notEqual(task.getOrderId(), appeal.getOrderId())) {
            // 共键错位：裁决建立在任务-订单-申诉三方一致之上，错位数据必须人工核查
            throw new JbkException("申诉共键数据异常，禁止裁决");
        }
        // E2E-03 验收 P1-2：裁决前完整共键栅栏（复用包A DeliveryLinkGuard，不写第二套判定）——
        // 订单存在且 ORDER_TYPE=3、订单/任务 userId 一致、任务外键指向该订单；任一失败零写入。
        WsOrder order = DeliveryLinkGuard.requireLinked(orderMapper.selectById(appeal.getOrderId()), task);
        if (ObjectUtil.notEqual(order.getOrderStatus(), TradeEnum.OrderStatus.FINISHED.getValue())) {
            // 裁决只允许发生在已完成订单上（申诉在 4已完成 上登记，裁决不放宽这一前提）
            throw new JbkException("裁决要求关联订单处于已完成状态");
        }
        if (ObjectUtil.notEqual(appeal.getUserId(), order.getUserId())) {
            // 三方 userId 必须一致：申诉归属与订单/任务归属错位属数据污染，禁止裁决
            throw new JbkException("申诉共键数据异常，禁止裁决");
        }
        // R0-2 数量边界：唯一判定在 AfterSaleStrategy（含「原因码 × 策略码」白名单——
        // PLACEMENT/OTHER 不支持资金补偿），且必须先于任何 CAS——越界拒绝时本事务零写入。
        // 上界所需的四个计数分别来自申诉行与任务行，此处只负责取值，不在本类复刻任何取舍规则。
        int approvedCount = AfterSaleStrategy.requireApprovedCount(strategy, appeal.getAppealReason(),
                bo.getApprovedCount(), appeal.getReceivedCount(),
                task.getDeliveryCount(), task.getActualDeliveryCount());
        int decided = appealMapper.update(null, Wrappers.lambdaUpdate(WsDeliveryAppeal.class)
                .set(WsDeliveryAppeal::getAppealStatus, outcome)
                .set(WsDeliveryAppeal::getHandleBy, adminUserId)
                .set(WsDeliveryAppeal::getHandleTime, now)
                .set(WsDeliveryAppeal::getHandleResult, bo.getHandleResult().trim())
                .set(WsDeliveryAppeal::getUpdateTime, now)
                .eq(WsDeliveryAppeal::getId, appealId)
                .eq(WsDeliveryAppeal::getAppealStatus, DeliveryEnum.AppealStatus.PENDING.getValue()));
        if (decided != 1) {
            throw new JbkException("申诉已被并发处理，请刷新后重试");
        }
        // 任务 7→5：申诉归档回签收终态（订单保持 4已完成，补送/资金动作由后续包执行）
        int taskMoved = taskMapper.update(null, Wrappers.lambdaUpdate(WsDeliveryTask.class)
                .set(WsDeliveryTask::getTaskStatus, DeliveryEnum.TaskStatus.SIGNED.getValue())
                .set(WsDeliveryTask::getVersion, task.getVersion() + 1)
                .set(WsDeliveryTask::getUpdateTime, now)
                .eq(WsDeliveryTask::getId, task.getId())
                .eq(WsDeliveryTask::getTaskStatus, DeliveryEnum.TaskStatus.APPEALING.getValue())
                .eq(WsDeliveryTask::getVersion, task.getVersion()));
        if (taskMoved != 1) {
            throw new JbkException("任务状态已变化，裁决失败");
        }
        // 售后执行动作与裁决同事务登记（REQUIRED）：裁决若回滚，待执行动作必须一并消失
        WsAfterSaleAction action = registerAfterSaleAction(strategy, approvedCount, appeal, task, order,
                adminUserId, now);
        messageService.sendInApp(appeal.getUserId(), MessageEnum.MsgDomain.DELIVERY, "申诉裁决结果",
                "您的申诉已裁决：" + outcomeEnum.getDesc() + "。" + bo.getHandleResult().trim()
                        + (AfterSaleStrategy.refundsAssets(strategy) ? " 补偿将返还至您的水卡。" : ""),
                "appeal", String.valueOf(appealId), now);
        // 裁决是关键状态变化（申诉 1→终态 + 任务 7→5）：走可靠路径（失败抛出整体回滚，P1-3）；
        // 动作者是后台管理员，身份走会话推断（MANAGE 正确），幂等键按申诉行唯一（一裁一痕）。
        domainEventService.recordReliableOnce(OpsEnum.EventType.DELIVERY_NODE, task.getTaskNo(),
                "APPEAL_DECIDE:" + appealId,
                DeliveryEnum.AppealStatus.PENDING.getValue() + ":待处理",
                outcome + ":" + outcomeEnum.getDesc() + "（appealId=" + appealId
                        + "，strategy=" + strategy.getCode() + "，approvedCount=" + approvedCount
                        + "，afterSaleNo=" + (ObjectUtil.isNull(action) ? null : action.getAfterSaleNo())
                        + "，time=" + now + "）");
        return true;
    }

    /**
     * 裁决产出的售后执行动作（E2E-04 包A 集成点）。
     *
     * <p><b>三条出口由策略码唯一决定，本方法不做二次判断</b>：
     * REJECT 零写入不建行（"申诉不成立"必须没有任何待执行动作，否则它迟早会被某个 Worker 捡起来执行）；
     * 三个资金策略建 {@code ACTION_TYPE=2卡内补偿} 的待执行行，由包A 返还内核在独立事务里执行；
     * RESEND 建 {@code ACTION_TYPE=4补送} 的待执行行，四元额度恒为 0——补送走履约不动资金，
     * 包A 的返还事务在入口就会拒绝这种类型，因此它在本包内是一条只登记不执行的待办，
     * 由包C 实现执行路径。</p>
     *
     * <p><b>额度只从订单冻结快照算，绝不出现 payWay 分支</b>：「哪个维度退多少」的全部知识在
     * {@link AfterSaleStrategy#compute}，这里只负责把三元结果搬进三个额度列。
     * 快照 fail-closed（{@link DeliveryRefundSnapshot#require}）：历史单价是返还与封顶的共同基准，
     * 缺失或不自洽时按当前价目表猜一个值出来，会与原扣款流水对不上。</p>
     *
     * <p><b>此处刻意不做累计封顶判定</b>：权威封顶只在「持卡行 X 锁 + READ_COMMITTED」的返还事务内
     * 成立（见 {@code AfterSaleQuota} 类注释）。在本事务里再判一次既不权威，又要引入
     * {@code sumSuccessRefundByOrderForUpdate} 这条 FOR UPDATE 聚合读——它在默认 RR 隔离级别下
     * 会在 {@code idx_after_sale_order} 上留 gap 锁，让两笔无关订单的并发裁决互等死锁。
     * 超额在执行期被拒绝并落「需人工对账」，运营看得到，不会静默多退。</p>
     *
     * @return 登记后的动作行；REJECT 返回 {@code null}
     */
    private WsAfterSaleAction registerAfterSaleAction(AfterSaleEnum.StrategyCode strategy, int approvedCount,
                                                      WsDeliveryAppeal appeal, WsDeliveryTask task, WsOrder order,
                                                      Long adminUserId, String now) {
        if (strategy == AfterSaleEnum.StrategyCode.REJECT) {
            return null;
        }
        DeliveryRefundSnapshot.Parsed snap = DeliveryRefundSnapshot.require(order);
        AfterSaleStrategy.Refund refund = AfterSaleStrategy.compute(strategy, approvedCount, snap);
        boolean refundsAssets = AfterSaleStrategy.refundsAssets(strategy);
        WsAfterSaleAction draft = new WsAfterSaleAction()
                .setSourceType(AfterSaleEnum.SourceType.DELIVERY_APPEAL.getValue())
                // 来源键取 appealId 而非 taskId：uk_appeal_active_task 只约束「待处理」申诉，
                // 同一任务可合法产生多条已裁决申诉，用 taskId 会把第二次合法申诉误判成重放
                .setSourceId(appeal.getId())
                .setOrderId(order.getId())
                .setUserId(order.getUserId())
                .setCardId(order.getCardId())
                .setActionType(refundsAssets
                        ? AfterSaleEnum.ActionType.CARD_COMPENSATE.getValue()
                        : AfterSaleEnum.ActionType.RESEND.getValue())
                .setStrategyCode(strategy.getCode())
                .setApprovedCount(approvedCount)
                .setRefundProductFen(refund.productFen())
                .setRefundServiceFen(refund.serviceFen())
                .setRefundProductMl(refund.productMl())
                .setCalcSnapshot(buildDecideSnapshot(strategy, approvedCount, appeal, task, snap, refund,
                        adminUserId, now))
                .setApproveBy(adminUserId)
                .setApproveTime(now);
        draft.setCreateBy(adminUserId);
        draft.setUpdateBy(adminUserId);
        // REFUND_AMOUNT、AFTER_SALE_NO、状态机起点与版本由内核事务钉死，编排层不得自报
        return afterSaleActionTxService.createPending(draft, now);
    }

    /**
     * 计算依据快照：出账即冻结，事后只读不重算。
     * <p>把「当时的数量上界」与「当时的单价」一并封存——两者都会随任务签收数据与价目表变化，
     * 事后重算得到的必然是另一组值，运营再也无法核对这笔补偿当初凭什么算出来。</p>
     */
    private String buildDecideSnapshot(AfterSaleEnum.StrategyCode strategy, int approvedCount,
                                       WsDeliveryAppeal appeal, WsDeliveryTask task,
                                       DeliveryRefundSnapshot.Parsed snap,
                                       AfterSaleStrategy.Refund refund, Long adminUserId, String now) {
        return JSONUtil.createObj()
                .set("strategyCode", strategy.getCode())
                .set("appealReason", appeal.getAppealReason())
                .set("receivedCount", appeal.getReceivedCount())
                .set("taskDeliveryCount", task.getDeliveryCount())
                .set("taskActualDeliveryCount", task.getActualDeliveryCount())
                .set("approvedCount", approvedCount)
                // 入参与上面 requireApprovedCount 逐一相同：封存的上界必须就是当时实际判定用的那个，
                // 换一组入参重算等于在台账上留下一个与判定无关的数字
                .set("maxApprovedCount", AfterSaleStrategy.maxApprovedCount(appeal.getAppealReason(),
                        appeal.getReceivedCount(), task.getDeliveryCount(), task.getActualDeliveryCount()))
                .set("payWay", snap.payWay())
                .set("snapDeliveryCount", snap.deliveryCount())
                .set("unitWaterPriceFen", snap.unitWaterPriceFen())
                .set("deliveryFeePerContainerFen", snap.deliveryFeePerContainerFen())
                .set("refundProductFen", refund.productFen())
                .set("refundServiceFen", refund.serviceFen())
                .set("refundProductMl", refund.productMl())
                .set("decidedBy", adminUserId)
                .set("decidedAt", now)
                .toString();
    }

    @Override
    public WsDeliveryAppeal getTaskAppealForCourier(String taskNo, Long actorUserId) {
        WsDeliveryTask task = requireAssignedCourierEvidenceAccess(taskNo, actorUserId);
        return appealMapper.selectOne(Wrappers.lambdaQuery(WsDeliveryAppeal.class)
                .eq(WsDeliveryAppeal::getTaskId, task.getId())
                .eq(WsDeliveryAppeal::getOrderId, task.getOrderId())
                .orderByDesc(WsDeliveryAppeal::getId)
                .last("LIMIT 1"));
    }

    @Override
    public List<WsDeliveryException> listTaskExceptionsForCourier(String taskNo, Long actorUserId) {
        WsDeliveryTask task = requireAssignedCourierEvidenceAccess(taskNo, actorUserId);
        return exceptionMapper.selectList(Wrappers.lambdaQuery(WsDeliveryException.class)
                .eq(WsDeliveryException::getTaskId, task.getId())
                .eq(WsDeliveryException::getCourierId, task.getCourierId())
                .orderByAsc(WsDeliveryException::getId));
    }

    /**
     * 只读证据访问收口（规则16 / Mock 第五轮口径）：只有任务实际归属配送员可查申诉与异常；
     * 未分配任务、他人任务（即使范围覆盖该站）一律拒绝；任务-订单共键错位 fail-closed。
     * 纯校验：拒绝路径不写审计、不发消息。
     */
    private WsDeliveryTask requireAssignedCourierEvidenceAccess(String taskNo, Long actorUserId) {
        CourierAccess.EnabledCourier courier = courierAccess.requireEnabledCourier(actorUserId);
        WsDeliveryTask task = requireTask(taskNo);
        if (ObjectUtil.isNull(task.getCourierId())
                || ObjectUtil.notEqual(task.getCourierId(), courier.courier().getId())) {
            throw new JbkException("只有任务归属配送员可以查看申诉与异常证据");
        }
        DeliveryLinkGuard.requireLinked(orderMapper.selectById(task.getOrderId()), task);
        return task;
    }

    private WsDeliveryTask requireTask(String taskNo) {
        if (StrUtil.isBlank(taskNo)) {
            throw new JbkException("配送任务不存在");
        }
        WsDeliveryTask task = taskMapper.selectOne(Wrappers.lambdaQuery(WsDeliveryTask.class)
                .eq(WsDeliveryTask::getTaskNo, taskNo.trim()));
        if (ObjectUtil.isNull(task)) {
            throw new JbkException("配送任务不存在");
        }
        return task;
    }

    private long decimalId(String raw, String field) {
        if (raw == null || !raw.trim().matches("^[1-9]\\d{0,17}$")) {
            throw new JbkException(field + " 必须是正十进制字符串");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new JbkException(field + " 越界");
        }
    }
}
