package com.jbk.serve.service.mall.impl;

import cn.hutool.json.JSONUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.mall.WsMallFulfillmentMapper;
import com.jbk.serve.mapper.mall.WsMallOrderMapper;
import com.jbk.serve.service.mall.IMallFulfillmentService;
import com.jbk.serve.service.mini.notify.WechatNotifyEnqueue;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.mini.WechatNotifyEnum;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.mall.bo.MallFulfillActionBo;
import com.jbk.tool.data.mall.bo.MallFulfillSignBo;
import com.jbk.tool.data.mall.po.WsMallFulfillment;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.vo.MallFulfillVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商城履约服务实现（E2E-09 S3）。状态推进只有一种写法：「精确前态+版本号」CAS，
 * 影响 0 行按状态已变化拒绝；轨迹/消息/审计写在 CAS 命中之后，轨迹幂等键是第二道保险。
 * 拒绝路径零副作用：任一共键不一致在改动任何一行之前抛出，事务整体回滚。
 *
 * @author dakang
 * @since 2026-08-09
 */
@Slf4j
@Service
public class MallFulfillmentServiceImpl implements IMallFulfillmentService {

    /** 轨迹幂等键前缀。 */
    static final String TRACE_KEY_PREFIX = MallFulfillCore.TRACE_KEY_PREFIX;
    /** 履约节点审计幂等键前缀：一个节点一条可靠事件。 */
    static final String AUDIT_KEY_PREFIX = MallFulfillCore.AUDIT_KEY_PREFIX;
    /** 系统动作审计人。 */
    private static final long SYSTEM_OPERATOR = 0L;

    /** 渠道无关的状态机原语：自营与第三方共用同一份，避免两条链各自漂移。 */
    /** 订阅通知登记：与站内信同事务——本事务回滚意味着那件事没发生，通知必须一起消失。 */
    @Autowired
    private WechatNotifyEnqueue notifyEnqueue;
    @Autowired
    private MallFulfillCore core;

    @Autowired
    private WsMallFulfillmentMapper fulfillMapper;
    @Autowired
    private WsMallOrderMapper orderMapper;
    @Autowired
    private IWsMessageService messageService;
    @Autowired
    private MallExchangeSettlement exchangeSettlement;

    // ==================== 生成 ====================

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallFulfillVo ensureTask(String orderNo) {
        WsMallOrder order = core.requirePayableOrder(orderNo);
        WsMallFulfillment existed = fulfillMapper.selectByOrderIdIncludingDeleted(order.getId());
        if (ObjectUtil.isNotNull(existed)) {
            // 只查 DATA_STATUS 不够：错位的既有任务会被当成幂等成功返回
            core.requireAlive(existed);
            return core.toVoChecked(existed, false);
        }
        String now = DateUtils.time();
        WsMallFulfillment task = new WsMallFulfillment()
                .setOrderId(order.getId())
                .setOrderNo(order.getOrderNo())
                .setUserId(order.getUserId())
                .setWarehouseId(order.getWarehouseId())
                .setFulfillStatus(MallEnum.FulfillStatus.PENDING_PICK.getValue())
                .setVersion(1)
                .setReceiverName(order.getReceiverName())
                .setReceiverPhone(order.getReceiverPhone())
                .setReceiverRegion(order.getReceiverRegion())
                .setReceiverAddress(order.getReceiverAddress())
                .setReceiverDistrictCode(order.getReceiverDistrictCode());
        task.setCreateTime(now);
        try {
            fulfillMapper.insert(task);
        }
        catch (DuplicateKeyException race) {
            // 并发建任务撞 uk(ORDER_ID)：一单一任务由库层兜住，输方重读赢家原样返回
            WsMallFulfillment winner = fulfillMapper.selectByOrderIdIncludingDeleted(order.getId());
            if (ObjectUtil.isNull(winner)) {
                throw new JbkException("履约任务生成冲突，请重试");
            }
            core.requireAlive(winner);
            return core.toVoChecked(winner, false);
        }
        core.writeTrace(task, MallEnum.FulfillStatus.PENDING_PICK, MallEnum.ActorType.SYSTEM,
                SYSTEM_OPERATOR, now, "支付完成，已生成履约任务");
        core.writeAudit(task, MallEnum.FulfillStatus.PENDING_PICK, OpsEnum.ActorPortal.SYSTEM,
                SYSTEM_OPERATOR, "支付完成，系统生成履约任务");
        return core.toVoChecked(task, false);
    }

    // ==================== 前置仓 ====================

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallFulfillVo pick(Long operatorId, MallFulfillActionBo bo) {
        WsMallFulfillment task = core.requireTask(bo.getOrderNo());
        core.requireWarehouseOperator(operatorId, task);
        WsMallOrder order = core.requireLinkedOrder(task);
        // 履约开始的唯一入口：订单必须精确处于 2「已支付待履约」
        if (!ObjectUtil.equal(order.getOrderStatus(), MallEnum.OrderStatus.PAID.getValue())) {
            throw new JbkException("订单不处于已支付待履约状态，无法开始履约");
        }
        String now = DateUtils.time();
        core.advance(task, MallEnum.FulfillStatus.PENDING_PICK, MallEnum.FulfillStatus.PENDING_PACK,
                "PICK_TIME", operatorId, now);
        // 订单与任务同事务推进：任务已开始而订单还停在待履约，两边就此各说各话
        int moved = orderMapper.casStatus(order.getId(),
                MallEnum.OrderStatus.PAID.getValue(),
                MallEnum.OrderStatus.FULFILLING.getValue(), operatorId, now);
        if (moved != 1) {
            throw new JbkException("订单状态已变化，拣货已中止，请刷新后重试");
        }
        core.writeTrace(task, MallEnum.FulfillStatus.PENDING_PACK, MallEnum.ActorType.WAREHOUSE,
                operatorId, now, "前置仓已拣货");
        core.writeAudit(task, MallEnum.FulfillStatus.PENDING_PACK, OpsEnum.ActorPortal.MANAGE,
                operatorId, "前置仓拣货，订单转履约中");
        return core.toVoChecked(core.requireTask(task.getOrderNo()), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallFulfillVo pack(Long operatorId, MallFulfillActionBo bo) {
        WsMallFulfillment task = core.requireTask(bo.getOrderNo());
        core.requireWarehouseOperator(operatorId, task);
        core.requireFulfillingOrder(task);
        String now = DateUtils.time();
        core.advance(task, MallEnum.FulfillStatus.PENDING_PACK, MallEnum.FulfillStatus.PENDING_ASSIGN,
                "PACK_TIME", operatorId, now);
        core.writeTrace(task, MallEnum.FulfillStatus.PENDING_ASSIGN, MallEnum.ActorType.WAREHOUSE,
                operatorId, now, "前置仓已打包，待分配配送员");
        core.writeAudit(task, MallEnum.FulfillStatus.PENDING_ASSIGN, OpsEnum.ActorPortal.MANAGE,
                operatorId, "前置仓打包完成");
        return core.toVoChecked(core.requireTask(task.getOrderNo()), true);
    }

    // ==================== 分配 ====================

    // ==================== 配送员 ====================

    // ==================== 用户签收 ====================

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallFulfillVo signByUser(Long userId, MallFulfillSignBo bo) {
        if (!MallEnum.SignMethod.isKnown(bo.getSignMethod())) {
            // 白名单：未知签收方式不得默认成"本人签收"，那是在替用户作证
            throw new JbkException("签收方式不合法");
        }
        WsMallFulfillment task = core.requireTask(bo.getOrderNo());
        if (!ObjectUtil.equal(task.getUserId(), userId)) {
            // 他人订单一律按不存在处理，不泄露存在性
            throw new JbkException("订单不存在");
        }
        WsMallOrder order = core.requireLinkedOrder(task);
        if (!ObjectUtil.equal(order.getOrderStatus(), MallEnum.OrderStatus.FULFILLING.getValue())) {
            throw new JbkException("订单不处于履约中状态，无法签收");
        }
        // 一个时间贯穿任务、订单、轨迹与消息：分开取会让同一次签收出现四个时刻。
        // 领域事件服务未接收本次业务时间，因此审计时间不属于签收业务时间同源集合
        String now = DateUtils.time();
        int signed = fulfillMapper.casSign(task.getId(),
                MallEnum.FulfillStatus.ARRIVED.getValue(),
                MallEnum.FulfillStatus.SIGNED.getValue(), task.getVersion(),
                bo.getSignMethod(), StrUtil.emptyToNull(StrUtil.trim(bo.getSignRemark())),
                userId, now);
        if (signed != 1) {
            throw new JbkException("订单未处于待签收状态或已签收，请刷新后重试");
        }
        task.setFulfillStatus(MallEnum.FulfillStatus.SIGNED.getValue())
                .setVersion(task.getVersion() + 1);
        int moved = orderMapper.casStatus(order.getId(),
                MallEnum.OrderStatus.FULFILLING.getValue(),
                MallEnum.OrderStatus.COMPLETED.getValue(), userId, now);
        if (moved != 1) {
            // 任务已签而订单没完成＝账实不符，整事务回滚重来，绝不留半截终态
            throw new JbkException("订单状态已变化，签收已中止，请刷新后重试");
        }
        // 换货补发单的实际出库发生在签收这一刻：货真的到了用户手上，预占才该转成出库。
        // 普通商城订单在整条 S3 履约链上零库存动作，这一支只对带来源标识的补发单生效。
        if (ObjectUtil.isNotNull(order.getSourceAfterSaleId())) {
            exchangeSettlement.settleOnSign(order, userId, now);
        }
        core.writeTrace(task, MallEnum.FulfillStatus.SIGNED, MallEnum.ActorType.USER,
                userId, now, MallEnum.SignMethod.SELF.getValue() == bo.getSignMethod()
                        ? "用户本人签收，订单完成" : "他人代收，订单完成");
        messageService.sendInApp(task.getUserId(), MessageEnum.MsgDomain.MALL,
                "商城订单已完成",
                "您的商城订单 " + task.getOrderNo() + " 已签收完成，感谢使用。",
                "mallOrder", task.getOrderNo(), now);
        notifyEnqueue.enqueue(WechatNotifyEnum.EventType.MALL_DELIVERED,
                WechatNotifyEnum.BizObjectType.MALL_ORDER, task.getOrderNo(), task.getUserId(),
                JSONUtil.createObj().set("time", now));
        core.writeAudit(task, MallEnum.FulfillStatus.SIGNED, OpsEnum.ActorPortal.USER,
                userId, "签收方式 " + bo.getSignMethod() + "，订单转已完成");
        return detailForUser(userId, task.getOrderNo());
    }

    // ==================== 查询 ====================

    @Override
    public MallFulfillVo detailForUser(Long userId, String orderNo) {
        WsMallFulfillment task = core.requireTask(orderNo);
        if (!ObjectUtil.equal(task.getUserId(), userId)) {
            throw new JbkException("订单不存在");
        }
        return core.toVoChecked(task, true);
    }

    @Override
    public MallFulfillVo detailForManage(Long operatorId, String orderNo) {
        WsMallFulfillment task = core.requireTask(orderNo);
        core.requireWarehouseOperator(operatorId, task);
        return core.toVoChecked(task, true);
    }

    // ==================== 校验 ====================

    /** 订单必须存在、未删除、精确处于已支付待履约。 */

    /** 配送员必须准入启用、分配证据自洽，且这条任务确实分配给了本人。 */

    // ==================== 推进与留痕 ====================

    /** 精确前态 + 版本号 CAS；影响 0 行一律拒绝，绝不改成"查一遍再更新"。 */

    /** 节点审计：显式身份 + 节点幂等键，与业务同事务，写入失败整体回滚。 */

    // ==================== 组装 ====================

    /** 唯一对外组装出口：先过关联校验，再组装——toVo 不得被未校验的任务直接调用。 */
}
