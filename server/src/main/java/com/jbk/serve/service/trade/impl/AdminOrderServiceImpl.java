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

import java.math.BigInteger;
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
        IPage<AdminOrderItemVo> result = orderMapper.pageAdminOrders(page, bo);
        List<AdminOrderItemVo> records = result.getRecords();
        records.forEach(this::decorateActorAndOwner);
        return PageDataVo.getPageData(records, result.getTotal());
    }

    @Override
    public AdminOrderTraceVo getOrderTrace(Long id) {
        AdminOrderItemVo order = orderMapper.selectAdminOrderById(id);
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
     * <p>分页与追溯两条查询路径都必须经过本方法：SQL 取出的持卡人原始手机号在此统一脱敏并清空，
     * 保证任何路径都不把明文号码序列化出接口；卡删除/查不到时相关字段保持空值（fail-soft，不 500）。</p>
     */
    private void decorateActorAndOwner(AdminOrderItemVo order) {
        order.setActorMaskedPhone(maskPhone(order.getUserPhone()));
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
        String mismatch = commandLinkMismatch(order, cmd);
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

    /** 指令类型(1320)：1=开始出水（取水订单唯一允许绑定的指令类型）。 */
    private static final int CMD_TYPE_START_DISPENSE = 1;
    /** 订单类型(1340)：1=扫码取水。 */
    private static final int ORDER_TYPE_WATER = 1;
    /** 订单类型(1340)：2=购卡充值。 */
    private static final int ORDER_TYPE_RECHARGE = 2;
    /** 订单类型(1340)：3=水配送。 */
    private static final int ORDER_TYPE_DELIVERY = 3;

    /**
     * 订单-指令共键校验（纯函数，供单元测试直接驱动）。
     *
     * @param cmd 可为 null（订单引用的指令不存在）
     * @return null 表示共键一致；否则返回不一致原因。
     */
    static String commandLinkMismatch(AdminOrderItemVo order, WsCommand cmd) {
        if (ObjectUtil.isNull(cmd)) {
            return "订单引用的指令（CMD_ID=" + order.getCmdId() + "）不存在，不能作为本单履约证据";
        }
        if (ObjectUtil.isNull(order.getCmdId()) || ObjectUtil.isNull(cmd.getId())
                || !cmd.getId().equals(order.getCmdId())) {
            return "订单 CMD_ID 与实际读取的指令主键不一致，不能作为本单履约证据";
        }
        // 收口复审：DATA_STATUS 必须精确为 0（正常）；为空同样 fail-closed，不默认视为未删除。
        Integer dataStatus = cmd.getDataStatus();
        if (ObjectUtil.isNull(dataStatus) || dataStatus != 0) {
            return "指令数据状态异常（DATA_STATUS=" + dataStatus + "），不能作为本单履约证据";
        }
        if (ObjectUtil.isNull(cmd.getOrderId())) {
            return "指令未关联任何订单（ORDER_ID 为空），不能作为本单履约证据";
        }
        if (!cmd.getOrderId().equals(order.getId())) {
            return "指令归属订单（" + cmd.getOrderId() + "）与当前订单（" + order.getId() + "）不一致";
        }
        if (ObjectUtil.isNull(order.getDeviceId()) || ObjectUtil.isNull(cmd.getDeviceId())) {
            return "订单或指令缺少设备标识，无法证明同一设备履约";
        }
        if (!cmd.getDeviceId().equals(order.getDeviceId())) {
            return "指令目标设备（" + cmd.getDeviceId() + "）与订单设备（" + order.getDeviceId() + "）不一致";
        }
        if (ObjectUtil.isNull(order.getOutletId()) || ObjectUtil.isNull(order.getOutletDeviceId())) {
            return "订单出水口不存在或缺少所属设备，无法证明出水口归属";
        }
        if (!order.getOutletDeviceId().equals(order.getDeviceId())) {
            return "订单出水口所属设备（" + order.getOutletDeviceId() + "）与订单设备（"
                    + order.getDeviceId() + "）不一致";
        }
        // 收口复审：订单只要引用了指令，就必须同时满足 ORDER_TYPE=1 且 CMD_TYPE=1，其他任何组合一律 mismatch。
        Integer orderType = order.getOrderType();
        boolean isWaterOrder = ObjectUtil.isNotNull(orderType) && orderType == ORDER_TYPE_WATER;
        boolean isDispense = ObjectUtil.isNotNull(cmd.getCmdType()) && cmd.getCmdType() == CMD_TYPE_START_DISPENSE;
        if (!isWaterOrder || !isDispense) {
            return "订单关联指令仅允许「扫码取水订单(1) ↔ 开始出水指令(1)」组合，当前订单类型（"
                    + orderType + "）/指令类型（" + cmd.getCmdType() + "）不符";
        }
        // 出水指令报文为空/畸形/缺 orderNo/orderNo 为空一律 mismatch；并核对 planMl、outletNo 与订单一致。
        String payload = cmd.getCmdPayload();
        if (StrUtil.isBlank(payload)) {
            return "出水指令报文为空，无法核验归属订单号";
        }
        cn.hutool.json.JSONObject json;
        try {
            json = cn.hutool.json.JSONUtil.parseObj(payload);
        } catch (Exception e) {
            return "出水指令报文不是合法 JSON，无法核验归属订单号";
        }
        Object orderNoValue = json.get("orderNo");
        if (!(orderNoValue instanceof String payloadOrderNo) || StrUtil.isBlank(payloadOrderNo)) {
            return "出水指令报文订单号（orderNo）缺失、为空或不是字符串，不能作为本单履约证据";
        }
        if (!payloadOrderNo.equals(order.getOrderNo())) {
            return "指令报文内订单号（" + payloadOrderNo + "）与当前订单（" + order.getOrderNo() + "）不一致";
        }
        // 出水计划量和出水口是履约证据必填共键；任一侧缺失、JSON 类型不是整数或数值不一致均 fail-closed。
        Long payloadPlanMl = strictJsonInteger(json.get("planMl"));
        if (ObjectUtil.isNull(payloadPlanMl)) {
            return "出水指令报文缺少整数计划水量（planMl），履约证据不完整";
        }
        if (payloadPlanMl <= 0) {
            return "出水指令报文计划水量（planMl）必须为正整数";
        }
        if (ObjectUtil.isNull(order.getPlanMl())) {
            return "订单缺少计划水量（PLAN_ML），无法核验指令履约证据";
        }
        if (order.getPlanMl() <= 0) {
            return "订单计划水量（PLAN_ML）必须为正整数，无法作为履约证据";
        }
        if (!payloadPlanMl.equals(order.getPlanMl())) {
            return "指令报文计划水量（" + payloadPlanMl + "）与订单计划水量（" + order.getPlanMl() + "）不一致";
        }
        Long payloadOutletNo = strictJsonInteger(json.get("outletNo"));
        if (ObjectUtil.isNull(payloadOutletNo)) {
            return "出水指令报文缺少整数出水口（outletNo），履约证据不完整";
        }
        if (payloadOutletNo <= 0) {
            return "出水指令报文出水口（outletNo）必须为正整数";
        }
        if (ObjectUtil.isNull(order.getOutletNo())) {
            return "订单缺少出水口（OUTLET_NO），无法核验指令履约证据";
        }
        if (order.getOutletNo() <= 0) {
            return "订单出水口（OUTLET_NO）必须为正整数，无法作为履约证据";
        }
        if (payloadOutletNo.longValue() != order.getOutletNo().longValue()) {
            return "指令报文出水口（" + payloadOutletNo + "）与订单出水口（" + order.getOutletNo() + "）不一致";
        }
        String stateMismatch = commandStateMismatch(order, cmd);
        if (StrUtil.isNotBlank(stateMismatch)) {
            return stateMismatch;
        }

        // 失败/超时可能发生在未出水阶段，因此双侧均无实际量时允许；一旦任一侧提供实际量，
        // 两侧必须同时提供严格非负整数且数值一致，避免异常单拼接到其他执行结果。
        if (ObjectUtil.isNotNull(order.getOrderStatus()) && order.getOrderStatus() == 6) {
            String actualMismatch = abnormalActualMismatch(order, cmd);
            if (StrUtil.isNotBlank(actualMismatch)) {
                return actualMismatch;
            }
        }

        // 完成/部分退款态核对实际水量，防「正确单号 + 错误水量」仍显示 ok。
        if (ObjectUtil.isNotNull(order.getOrderStatus())
                && (order.getOrderStatus() == 4 || order.getOrderStatus() == 8)) {
            if (ObjectUtil.isNull(order.getActualMl())) {
                return "订单已结算但缺少实际水量（ACTUAL_ML），履约证据不完整";
            }
            if (order.getActualMl() <= 0) {
                return "订单已结算但实际水量（ACTUAL_ML）不是正整数，履约证据不合法";
            }
            Long resultActualMl = null;
            if (StrUtil.isNotBlank(cmd.getResultPayload())) {
                try {
                    resultActualMl = strictJsonInteger(
                            cn.hutool.json.JSONUtil.parseObj(cmd.getResultPayload()).get("actualMl"));
                } catch (Exception ignored) {
                    resultActualMl = null;
                }
            }
            if (ObjectUtil.isNull(resultActualMl)) {
                return "订单已结算但指令结果报文缺少实际水量（actualMl），履约证据不完整";
            }
            if (resultActualMl <= 0) {
                return "订单已结算但指令结果实际水量（actualMl）不是正整数";
            }
            if (!resultActualMl.equals(order.getActualMl())) {
                return "指令结果实际水量（" + resultActualMl + "）与订单实际水量（" + order.getActualMl() + "）不一致";
            }
        }
        if (ObjectUtil.isNotNull(order.getOrderStatus()) && order.getOrderStatus() == 7) {
            Long resultActualMl = null;
            if (StrUtil.isNotBlank(cmd.getResultPayload())) {
                try {
                    resultActualMl = strictJsonInteger(
                            cn.hutool.json.JSONUtil.parseObj(cmd.getResultPayload()).get("actualMl"));
                } catch (Exception ignored) {
                    resultActualMl = null;
                }
            }
            if (ObjectUtil.isNull(order.getActualMl()) || order.getActualMl() != 0L
                    || ObjectUtil.isNull(resultActualMl) || resultActualMl != 0L) {
                return "零出水退款订单必须由实际水量为 0 的成功指令结果佐证";
            }
        }
        return null;
    }

    /** 只接受当前真实状态机能够产生的订单/指令状态与时间证据组合。 */
    private static String commandStateMismatch(AdminOrderItemVo order, WsCommand cmd) {
        Integer orderStatus = order.getOrderStatus();
        Integer cmdStatus = cmd.getCmdStatus();
        if (ObjectUtil.isNull(orderStatus)) {
            return "订单状态为空，无法核验指令履约阶段";
        }
        if (ObjectUtil.isNotNull(cmdStatus)) {
            if (cmdStatus == 1 && (StrUtil.isNotBlank(cmd.getSentTime())
                    || StrUtil.isNotBlank(cmd.getAckTime())
                    || StrUtil.isNotBlank(cmd.getFinishTime())
                    || StrUtil.isNotBlank(cmd.getResultPayload()))) {
                return "待下发指令不得携带下发、回执、完成时间或执行结果";
            }
            if (cmdStatus == 2) {
                if (StrUtil.isBlank(cmd.getSentTime())) {
                    return "已下发指令缺少下发时间";
                }
                if (StrUtil.isNotBlank(cmd.getAckTime())
                        || StrUtil.isNotBlank(cmd.getFinishTime())
                        || StrUtil.isNotBlank(cmd.getResultPayload())) {
                    return "已下发指令不得提前携带回执、完成时间或执行结果";
                }
            }
            if (cmdStatus == 3) {
                if (StrUtil.isBlank(cmd.getSentTime()) || StrUtil.isBlank(cmd.getAckTime())) {
                    return "已回执指令必须具备下发和回执时间";
                }
                if (StrUtil.isNotBlank(cmd.getFinishTime()) || StrUtil.isNotBlank(cmd.getResultPayload())) {
                    return "已回执指令不得提前携带完成时间或执行结果";
                }
            }
        }
        if (orderStatus == 2 && ObjectUtil.isNotNull(cmdStatus) && (cmdStatus == 1 || cmdStatus == 2)) {
            return null;
        }
        if (orderStatus == 3) {
            if (ObjectUtil.isNull(cmdStatus) || cmdStatus != 3
                    || StrUtil.isBlank(cmd.getSentTime()) || StrUtil.isBlank(cmd.getAckTime())) {
                return "出水中订单必须关联已回执指令，并具备下发/回执时间";
            }
            return null;
        }
        if (orderStatus == 4 || orderStatus == 7 || orderStatus == 8) {
            // 设备协议允许未先上报 ACK 而直接返回 result，因此成功终态不强制 ACK_TIME。
            if (ObjectUtil.isNull(cmdStatus) || cmdStatus != 4
                    || StrUtil.isBlank(cmd.getSentTime()) || StrUtil.isBlank(cmd.getFinishTime())) {
                return "已结算订单必须关联执行成功指令，并具备下发/完成时间";
            }
            if (StrUtil.isBlank(order.getFinishTime())) {
                return "已结算订单缺少订单完成时间，无法与指令终态相互佐证";
            }
            return null;
        }
        if (orderStatus == 6) {
            if (ObjectUtil.isNull(cmdStatus) || (cmdStatus != 5 && cmdStatus != 6)
                    || StrUtil.isBlank(cmd.getFinishTime())) {
                return "异常待补偿订单必须关联失败/超时终态指令，并具备完成时间";
            }
            return null;
        }
        return "订单状态（" + orderStatus + "）与指令状态（" + cmdStatus + "）不属于允许的履约证据组合";
    }

    /** 异常订单实际量证据：双侧均无值允许；任一侧有值时必须双侧严格非负且相等。 */
    private static String abnormalActualMismatch(AdminOrderItemVo order, WsCommand cmd) {
        Long orderActualMl = order.getActualMl();
        boolean resultActualPresent = false;
        Long resultActualMl = null;
        if (StrUtil.isNotBlank(cmd.getResultPayload())) {
            cn.hutool.json.JSONObject resultJson;
            try {
                resultJson = cn.hutool.json.JSONUtil.parseObj(cmd.getResultPayload());
            } catch (Exception ignored) {
                return "异常指令结果报文不是合法 JSON，无法核验实际水量";
            }
            resultActualPresent = resultJson.containsKey("actualMl");
            if (resultActualPresent) {
                resultActualMl = strictJsonInteger(resultJson.get("actualMl"));
                if (ObjectUtil.isNull(resultActualMl)) {
                    return "异常指令结果实际水量（actualMl）必须为整数数值";
                }
            }
        }
        if (ObjectUtil.isNull(orderActualMl) && !resultActualPresent) {
            return null;
        }
        if (ObjectUtil.isNull(orderActualMl) || !resultActualPresent) {
            return "异常订单与指令结果的实际水量必须同时存在或同时为空";
        }
        if (orderActualMl < 0 || resultActualMl < 0) {
            return "异常订单与指令结果的实际水量必须为非负整数";
        }
        if (!resultActualMl.equals(orderActualMl)) {
            return "异常指令结果实际水量（" + resultActualMl + "）与订单实际水量（"
                    + orderActualMl + "）不一致";
        }
        return null;
    }

    /** JSON 履约量只接受整数数值；字符串、浮点数、布尔值和越界整数均视为证据类型错误。 */
    private static Long strictJsonInteger(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger integer) {
            try {
                return integer.longValueExact();
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        return null;
    }

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
