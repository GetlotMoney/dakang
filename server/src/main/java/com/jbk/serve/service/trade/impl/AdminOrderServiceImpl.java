package com.jbk.serve.service.trade.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.delivery.IAdminDeliveryService;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.mini.recharge.RechargeDetailVerifier;
import com.jbk.serve.service.trade.IAdminOrderService;
import com.jbk.serve.service.trade.OrderCommandVerifier;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.trade.bo.AdminOrderBo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.trade.vo.AdminCommandTraceVo;
import com.jbk.tool.data.trade.vo.AdminOrderItemVo;
import com.jbk.tool.data.trade.vo.AdminOrderTraceVo;
import com.jbk.tool.data.trade.vo.AdminRechargeTraceVo;
import com.jbk.tool.data.trade.vo.CommandTraceNodeVo;
import com.jbk.tool.data.trade.vo.FlowTraceVo;
import com.jbk.tool.data.trade.vo.MiniRechargeDetailVo;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理端订单查询服务实现。
 * <p>只读：分页/详情仅查询 ws_order/ws_command/ws_wallet_flow，不做任何资金写操作（铁律1）。</p>
 *
 * @author dakang
 * @since 2026-07-20
 */
@Service
@RequiredArgsConstructor
public class AdminOrderServiceImpl implements IAdminOrderService {

    private final WsOrderMapper orderMapper;
    private final WsWalletFlowMapper walletFlowMapper;
    private final IWsCommandService commandService;
    private final RechargeIdentityMapper rechargeIdentityMapper;
    private final RechargeDetailVerifier rechargeDetailVerifier;
    private final IAdminDeliveryService adminDeliveryService;

    @Override
    public PageDataVo<AdminOrderItemVo> pageOrders(AdminOrderBo bo) {
        Page<AdminOrderItemVo> page = new Page<>(bo.getCurrent(), bo.getSize());
        IPage<AdminOrderItemVo> result = orderMapper.pageAdminOrders(page, bo,
                AfterSaleEnum.SourceType.WATER_ABNORMAL.getValue(),
                AfterSaleEnum.ActionStatus.SUCCESS.getValue());
        List<AdminOrderItemVo> records = result.getRecords();
        records.forEach(this::decorateActorAndOwner);
        return PageDataVo.getPageData(records, result.getTotal());
    }

    @Override
    public AdminOrderTraceVo getOrderTrace(Long id) {
        AdminOrderItemVo order = orderMapper.selectAdminOrderById(id,
                AfterSaleEnum.SourceType.WATER_ABNORMAL.getValue(),
                AfterSaleEnum.ActionStatus.SUCCESS.getValue());
        if (ObjectUtil.isNull(order)) {
            throw new JbkException("订单不存在");
        }
        decorateActorAndOwner(order);
        AdminOrderTraceVo trace = new AdminOrderTraceVo();
        trace.setOrder(order);
        trace.setCommand(buildCommandTrace(order));
        trace.setRecharge(buildRechargeTrace(order));
        // 充值单的余额、水量双维权益由 recharge.detail 返回。旧单维流水投影会在同时有
        // 余额赠送与水量时吞掉一维，因此充值单禁止再走该展示契约。
        trace.setFlows(ObjectUtil.equals(order.getOrderType(), ORDER_TYPE_RECHARGE)
                ? new ArrayList<>() : listFlows(id));
        if (ObjectUtil.equals(order.getOrderType(), ORDER_TYPE_DELIVERY)) {
            // E2E-03 包C：配送单三区块真实聚合（履约链/资金链/申诉逐行 fail-closed 核验）。
            // 取水/充值单保持 null/空数组——审计聚合先只覆盖配送域，避免本包扩大其他链行为。
            var deliveryTrace = adminDeliveryService.buildOrderTrace(order);
            trace.setDelivery(deliveryTrace.getDelivery());
            trace.setAppeals(deliveryTrace.getAppeals());
            trace.setAuditEvents(deliveryTrace.getAuditEvents());
        } else {
            trace.setDelivery(null);
            trace.setAppeals(new ArrayList<>());
            trace.setAuditEvents(new ArrayList<>());
        }
        return trace;
    }

    /**
     * UI-TRACE 读侧身份聚合：使用人/持卡人两个身份和用卡角色由服务端算好下发，前端不做推导。
     * <p>分页与追溯两条查询路径都必须经过本方法：SQL 取出的使用人与持卡人原始手机号在此统一脱敏并清空，
     * 保证任何路径都不把明文号码序列化出接口；卡删除/查不到时相关字段保持空值（fail-soft，不 500）。</p>
     * <p>使用人号码曾只脱敏出 actorMaskedPhone、原值列仍照常序列化，任一后台会话翻页即可导出全量
     * C 端明文号码；现在与持卡人同口径——原值列 @JsonIgnore + 此处置空双保险。</p>
     */
    private void decorateActorAndOwner(AdminOrderItemVo order) {
        order.setActorMaskedPhone(maskPhone(order.getUserPhoneRaw()));
        order.setUserPhoneRaw(null);
        order.setCardOwnerMaskedPhone(maskPhone(order.getCardOwnerPhoneRaw()));
        order.setCardOwnerPhoneRaw(null);
        order.setAccessRole(resolveAccessRole(order.getUserId(), order.getCardOwnerUserId()));
    }

    /**
     * 用卡角色判定（纯函数，供单元测试直接驱动）：使用人==持卡人 → OWNER，否则 MEMBER。
     * 无卡、卡被删或持卡人不可知时返回 null——角色证据缺失时不猜测，前端按空展示。
     */
    static String resolveAccessRole(Long actorUserId, Long cardOwnerUserId) {
        if (ObjectUtil.isNull(actorUserId) || ObjectUtil.isNull(cardOwnerUserId)) {
            return null;
        }
        return actorUserId.equals(cardOwnerUserId) ? "OWNER" : "MEMBER";
    }

    /** 11 位手机号脱敏为 138****5678；长度异常整体屏蔽为空串，绝不回落明文（与 MiniCardServiceImpl 同规则）。 */
    static String maskPhone(String phone) {
        // 唯一实现在 PhoneMask：脱敏规则不许分叉（曾两处各写一份）
        return com.jbk.tool.utils.PhoneMask.mask(phone);
    }

    /**
     * 复用小程序订单详情已经落地的充值共键与账本校验，不在 PC 查询层复制第二套资金算法。
     * 查询或校验失败时返回 mismatch，前端不得继续拼接支付成功、发卡或入账证据。
     */
    private AdminRechargeTraceVo buildRechargeTrace(AdminOrderItemVo adminOrder) {
        if (!ObjectUtil.equals(adminOrder.getOrderType(), ORDER_TYPE_RECHARGE)) {
            return null;
        }
        AdminRechargeTraceVo trace = new AdminRechargeTraceVo();
        try {
            List<WsOrder> rows = rechargeIdentityMapper
                    .selectOrdersByOrderNoIncludingDeleted(adminOrder.getOrderNo());
            if (rows == null || rows.size() != 1 || !ObjectUtil.equals(rows.get(0).getDataStatus(), 0)) {
                trace.setLinkStatus("mismatch");
                trace.setLinkReason("充值订单主体缺失、重复或处于删除态");
                return trace;
            }
            WsOrder verifiedOrder = rows.get(0);
            String mismatch = rechargeOrderMismatch(adminOrder, verifiedOrder);
            if (StrUtil.isNotBlank(mismatch)) {
                trace.setLinkStatus("mismatch");
                trace.setLinkReason(mismatch);
                return trace;
            }
            MiniRechargeDetailVo detail = rechargeDetailVerifier.verify(verifiedOrder);
            if (!Boolean.TRUE.equals(detail.getSnapshotValid())) {
                trace.setLinkStatus("mismatch");
                trace.setLinkReason("充值订单快照无效");
                return trace;
            }
            trace.setLinkStatus("ok");
            trace.setDetail(detail);
            return trace;
        } catch (RuntimeException failure) {
            trace.setLinkStatus("mismatch");
            trace.setLinkReason(StrUtil.blankToDefault(failure.getMessage(), "充值关联证据校验失败"));
            return trace;
        }
    }

    /** 两次只读之间若订单发生变化，同样按不一致处理，不混用两个时点的证据。 */
    static String rechargeOrderMismatch(AdminOrderItemVo adminOrder, WsOrder verifiedOrder) {
        if (ObjectUtil.isNull(verifiedOrder)) {
            return "充值订单结构化详情缺失";
        }
        if (!ObjectUtil.equals(adminOrder.getId(), verifiedOrder.getId())
                || !StrUtil.equals(adminOrder.getOrderNo(), verifiedOrder.getOrderNo())
                || !ObjectUtil.equals(adminOrder.getUserId(), verifiedOrder.getUserId())
                || !ObjectUtil.equals(adminOrder.getOrderType(), verifiedOrder.getOrderType())
                || !ObjectUtil.equals(adminOrder.getOrderStatus(), verifiedOrder.getOrderStatus())
                || !ObjectUtil.equals(adminOrder.getOrderAmount(), verifiedOrder.getOrderAmount())
                || !ObjectUtil.equals(adminOrder.getPayWay(), verifiedOrder.getPayWay())
                || !ObjectUtil.equals(adminOrder.getCardId(), verifiedOrder.getCardId())) {
            return "订单列表记录与结构化充值详情共键不一致";
        }
        return null;
    }

    /**
     * 由 ws_command 时间戳与状态重建指令追溯时间线（deviceNo 复用订单已关联设备编号）。
     * <p>fail-closed（2026-07-20 收口轮复审加固）：订单引用的指令必须存在、未逻辑删除，且订单归属、
     * 设备、指令类型、报文订单号全部共键一致；任一不满足即 linkStatus=mismatch，
     * 且不提供报文、指令状态与时间线，避免把缺失/他单/错设备/错类型指令拼接成本单完成证据。</p>
     */
    private AdminCommandTraceVo buildCommandTrace(AdminOrderItemVo order) {
        if (ObjectUtil.isNull(order.getCmdId())) {
            // 收口复审：已经过设备下发的取水订单（出水中 3/已完成 4/异常待补偿 6/已退款 7/部分退款 8）
            // 必然存在出水指令，CMD_ID 为空即履约证据缺失，必须呈现 mismatch，不得静默省略指令区块。
            // 状态 8 部分退款同 7：源于真实出水结算，缺指令同样是证据缺失（规则冻结）；
            // 状态 5 已取消可能在下发前取消，不强制要求指令。
            Integer orderType = order.getOrderType();
            Integer orderStatus = order.getOrderStatus();
            if (ObjectUtil.isNotNull(orderType) && orderType == ORDER_TYPE_WATER
                    && ObjectUtil.isNotNull(orderStatus)
                    && (orderStatus == 3 || orderStatus == 4 || orderStatus == 6
                        || orderStatus == 7 || orderStatus == 8)) {
                AdminCommandTraceVo missing = new AdminCommandTraceVo();
                missing.setLinkStatus("mismatch");
                missing.setLinkReason("取水订单已处于出水/完成/异常/退款状态但未关联任何出水指令（CMD_ID 为空），履约证据缺失");
                missing.setDeviceNo(order.getDeviceNo());
                return missing;
            }
            return null;
        }
        WsCommand cmd = commandService.getById(order.getCmdId());
        AdminCommandTraceVo vo = new AdminCommandTraceVo();
        vo.setCmdId(order.getCmdId());
        vo.setDeviceNo(order.getDeviceNo());
        // 共键/状态矩阵判定统一在 OrderCommandVerifier（读写两侧共用），本层只负责取值与投影。
        // afterSaleConfirmed 由订单查询 SQL 一并带出（EXISTS 子查询），不在此处逐行查库造成 N+1。
        String mismatch = OrderCommandVerifier.commandLinkMismatch(
                order.getId(), order.getOrderNo(), order.getOrderType(), order.getOrderStatus(),
                order.getDeviceId(), order.getOutletId(), order.getOutletNo(), order.getOutletDeviceId(),
                order.getPlanMl(), order.getActualMl(), order.getCmdId(), order.getFinishTime(),
                Boolean.TRUE.equals(order.getAfterSaleConfirmed()), cmd);
        if (StrUtil.isNotBlank(mismatch)) {
            vo.setLinkStatus("mismatch");
            vo.setLinkReason(mismatch);
            if (ObjectUtil.isNotNull(cmd)) {
                vo.setCmdNo(cmd.getCmdNo());
            }
            return vo;
        }
        vo.setLinkStatus("ok");
        vo.setCmdNo(cmd.getCmdNo());
        vo.setCmdType(cmd.getCmdType());
        vo.setCmdStatus(cmd.getCmdStatus());
        vo.setPayload(cmd.getCmdPayload());
        vo.setTimeline(buildTimeline(cmd));
        return vo;
    }

    /** 订单类型(1340)：1=扫码取水。 */
    private static final int ORDER_TYPE_WATER = 1;
    /** 订单类型(1340)：2=购卡充值。 */
    private static final int ORDER_TYPE_RECHARGE = 2;
    /** 订单类型(1340)：3=水配送。 */
    private static final int ORDER_TYPE_DELIVERY = 3;

    private List<CommandTraceNodeVo> buildTimeline(WsCommand cmd) {
        List<CommandTraceNodeVo> timeline = new ArrayList<>();
        if (StrUtil.isNotBlank(cmd.getSentTime())) {
            timeline.add(node("created", "指令下发", cmd.getSentTime(), null, "info"));
        }
        if (StrUtil.isNotBlank(cmd.getAckTime())) {
            timeline.add(node("ack", "设备回执 ACK", cmd.getAckTime(), null, "primary"));
        }
        if (StrUtil.isNotBlank(cmd.getFinishTime())) {
            Integer st = cmd.getCmdStatus();
            int status = ObjectUtil.defaultIfNull(st, 0);
            switch (status) {
                case 4:
                    timeline.add(node("result", "执行成功", cmd.getFinishTime(), cmd.getResultPayload(), "success"));
                    break;
                case 5:
                    timeline.add(node("result", "执行失败", cmd.getFinishTime(), cmd.getFailReason(), "danger"));
                    break;
                case 6:
                    timeline.add(node("timeout", "超时", cmd.getFinishTime(), cmd.getFailReason(), "danger"));
                    break;
                case 7:
                    timeline.add(node("result", "部分完成", cmd.getFinishTime(),
                            StrUtil.blankToDefault(cmd.getResultPayload(), cmd.getFailReason()), "warning"));
                    break;
                default:
                    timeline.add(node("result", "已终态", cmd.getFinishTime(), cmd.getResultPayload(), "info"));
            }
        }
        return timeline;
    }

    private CommandTraceNodeVo node(String node, String label, String time, String detail, String tone) {
        CommandTraceNodeVo vo = new CommandTraceNodeVo();
        vo.setNode(node);
        vo.setNodeLabel(label);
        vo.setTime(time);
        vo.setDetail(detail);
        vo.setTone(tone);
        return vo;
    }

    /** 订单关联流水（按 ID 升序，逻辑删除由 @TableLogic 自动过滤）。 */
    private List<FlowTraceVo> listFlows(Long orderId) {
        List<WsWalletFlow> flows = walletFlowMapper.selectList(Wrappers.lambdaQuery(WsWalletFlow.class)
                .eq(WsWalletFlow::getOrderId, orderId)
                .orderByAsc(WsWalletFlow::getId));
        List<FlowTraceVo> list = new ArrayList<>(flows.size());
        for (WsWalletFlow f : flows) {
            list.add(toFlowTrace(f));
        }
        return list;
    }

    private FlowTraceVo toFlowTrace(WsWalletFlow f) {
        FlowTraceVo vo = new FlowTraceVo();
        vo.setFlowId(f.getId());
        vo.setFlowType(flowTypeLabel(f.getFlowType()));
        Long ml = f.getMlChange();
        if (ml != null && ml != 0L) {
            vo.setAmount(ml);
            vo.setUnit("毫升");
        } else {
            vo.setAmount(ObjectUtil.defaultIfNull(f.getAmountChange(), 0L));
            vo.setUnit("分");
        }
        // P1-B：随流水下发写入时冻结的 AFTER 快照——「本单结算后余额/水量」只能取本单最后一条
        // 有效流水的 AFTER，不得拿 ws_card 当前值冒充「本订单终值」（后续充值/取水会改变卡面值）。
        vo.setAmountAfter(f.getAmountAfter());
        vo.setMlAfter(f.getMlAfter());
        vo.setTime(f.getCreateTime());
        vo.setRemark(f.getFlowRemark());
        return vo;
    }

    /** FLOW_TYPE(1344) → 展示标签。 */
    private String flowTypeLabel(Integer flowType) {
        int t = ObjectUtil.defaultIfNull(flowType, 0);
        switch (t) {
            case 1:
                return "充值入账";
            case 2:
                return "取水扣减";
            case 3:
                return "退款返还";
            case 4:
                return "补偿入账";
            case 5:
                return "后台调整";
            case 6:
                return "过期清零";
            default:
                return "流水";
        }
    }
}
