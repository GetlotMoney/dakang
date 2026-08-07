package com.jbk.serve.service.trade;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.device.po.WsCommand;

import java.math.BigInteger;

/**
 * 订单-出水指令共键与状态矩阵核验的唯一出处（纯函数，无状态、无注入）。
 *
 * <p><b>为什么入参是原始值而不是读模型 Vo</b>：本判定原先长在 {@code AdminOrderServiceImpl} 内部，
 * 签名吃 {@code AdminOrderItemVo}，于是只有 PC 追溯这一条读路径能调用；写路径（取水异常核账、
 * 后续售后执行）手里只有 {@code WsOrder} PO，想复用就只能复制一份，两份从落地第一天起就会漂移
 * （铁律⑤）。改成订单字段逐个传值后，读写两侧才可能真共用同一份矩阵。</p>
 *
 * <p><b>fail-closed</b>：任一共键、报文、状态或时间证据缺失/不一致都返回不一致原因，
 * 调用方据此只呈现「数据异常」，绝不把断链数据拼成履约证据。返回 null 才代表全部核验通过。</p>
 *
 * <p>状态数字一律取自 {@link TradeEnum.OrderStatus} 与 {@link DeviceEnum.CmdStatus}/{@link DeviceEnum.CmdType}，
 * 本类不定义任何状态字面量常量。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public final class OrderCommandVerifier {

    private OrderCommandVerifier() {
    }

    /**
     * 订单-指令共键 + 报文 + 状态时间 + 水量证据的整体核验。
     *
     * @param orderId            订单主键
     * @param orderNo            订单号（与指令报文 orderNo 互为共键）
     * @param orderType          订单类型(1340)；只有 1扫码取水 允许绑定出水指令
     * @param orderStatus        订单状态(1341)
     * @param orderDeviceId      订单设备ID
     * @param outletId           订单出水口ID
     * @param outletNo           订单出水口序号（与指令报文 outletNo 互为共键）
     * @param outletDeviceId     出水口所属设备ID（用于证明出水口归属订单设备）
     * @param planMl             订单计划水量(毫升)
     * @param actualMl           订单实际水量(毫升)
     * @param cmdId              订单引用的指令ID
     * @param orderFinishTime    订单完成时间
     * @param afterSaleConfirmed 该订单是否已经过取水异常核账确认（{@code ws_after_sale_action}
     *                           存在 SOURCE_TYPE=3取水异常核账 且 ACTION_STATUS=3已完成 的行）；
     *                           详见 {@link #commandStateMismatch}
     * @param cmd                实际读取到的指令行，可为 null（订单引用的指令不存在）
     * @return null 表示共键一致；否则返回不一致原因
     */
    public static String commandLinkMismatch(Long orderId, String orderNo, Integer orderType, Integer orderStatus,
                                             Long orderDeviceId, Long outletId, Integer outletNo, Long outletDeviceId,
                                             Long planMl, Long actualMl, Long cmdId, String orderFinishTime,
                                             boolean afterSaleConfirmed, WsCommand cmd) {
        if (ObjectUtil.isNull(cmd)) {
            return "订单引用的指令（CMD_ID=" + cmdId + "）不存在，不能作为本单履约证据";
        }
        if (ObjectUtil.isNull(cmdId) || ObjectUtil.isNull(cmd.getId()) || !cmd.getId().equals(cmdId)) {
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
        if (!cmd.getOrderId().equals(orderId)) {
            return "指令归属订单（" + cmd.getOrderId() + "）与当前订单（" + orderId + "）不一致";
        }
        if (ObjectUtil.isNull(orderDeviceId) || ObjectUtil.isNull(cmd.getDeviceId())) {
            return "订单或指令缺少设备标识，无法证明同一设备履约";
        }
        if (!cmd.getDeviceId().equals(orderDeviceId)) {
            return "指令目标设备（" + cmd.getDeviceId() + "）与订单设备（" + orderDeviceId + "）不一致";
        }
        if (ObjectUtil.isNull(outletId) || ObjectUtil.isNull(outletDeviceId)) {
            return "订单出水口不存在或缺少所属设备，无法证明出水口归属";
        }
        if (!outletDeviceId.equals(orderDeviceId)) {
            return "订单出水口所属设备（" + outletDeviceId + "）与订单设备（" + orderDeviceId + "）不一致";
        }
        // 收口复审：订单只要引用了指令，就必须同时满足 ORDER_TYPE=1 且 CMD_TYPE=1，其他任何组合一律 mismatch。
        boolean isWaterOrder = ObjectUtil.isNotNull(orderType)
                && orderType == TradeEnum.OrderType.WATER.getValue();
        boolean isDispense = ObjectUtil.isNotNull(cmd.getCmdType())
                && cmd.getCmdType() == DeviceEnum.CmdType.START_DISPENSE.getValue();
        if (!isWaterOrder || !isDispense) {
            return "订单关联指令仅允许「扫码取水订单(1) ↔ 开始出水指令(1)」组合，当前订单类型（"
                    + orderType + "）/指令类型（" + cmd.getCmdType() + "）不符";
        }
        // 出水指令报文为空/畸形/缺 orderNo/orderNo 为空一律 mismatch；并核对 planMl、outletNo 与订单一致。
        String payload = cmd.getCmdPayload();
        if (StrUtil.isBlank(payload)) {
            return "出水指令报文为空，无法核验归属订单号";
        }
        JSONObject json;
        try {
            json = JSONUtil.parseObj(payload);
        } catch (Exception e) {
            return "出水指令报文不是合法 JSON，无法核验归属订单号";
        }
        Object orderNoValue = json.get("orderNo");
        if (!(orderNoValue instanceof String payloadOrderNo) || StrUtil.isBlank(payloadOrderNo)) {
            return "出水指令报文订单号（orderNo）缺失、为空或不是字符串，不能作为本单履约证据";
        }
        if (!payloadOrderNo.equals(orderNo)) {
            return "指令报文内订单号（" + payloadOrderNo + "）与当前订单（" + orderNo + "）不一致";
        }
        // 出水计划量和出水口是履约证据必填共键；任一侧缺失、JSON 类型不是整数或数值不一致均 fail-closed。
        Long payloadPlanMl = strictJsonInteger(json.get("planMl"));
        if (ObjectUtil.isNull(payloadPlanMl)) {
            return "出水指令报文缺少整数计划水量（planMl），履约证据不完整";
        }
        if (payloadPlanMl <= 0) {
            return "出水指令报文计划水量（planMl）必须为正整数";
        }
        if (ObjectUtil.isNull(planMl)) {
            return "订单缺少计划水量（PLAN_ML），无法核验指令履约证据";
        }
        if (planMl <= 0) {
            return "订单计划水量（PLAN_ML）必须为正整数，无法作为履约证据";
        }
        if (!payloadPlanMl.equals(planMl)) {
            return "指令报文计划水量（" + payloadPlanMl + "）与订单计划水量（" + planMl + "）不一致";
        }
        Long payloadOutletNo = strictJsonInteger(json.get("outletNo"));
        if (ObjectUtil.isNull(payloadOutletNo)) {
            return "出水指令报文缺少整数出水口（outletNo），履约证据不完整";
        }
        if (payloadOutletNo <= 0) {
            return "出水指令报文出水口（outletNo）必须为正整数";
        }
        if (ObjectUtil.isNull(outletNo)) {
            return "订单缺少出水口（OUTLET_NO），无法核验指令履约证据";
        }
        if (outletNo <= 0) {
            return "订单出水口（OUTLET_NO）必须为正整数，无法作为履约证据";
        }
        if (payloadOutletNo.longValue() != outletNo.longValue()) {
            return "指令报文出水口（" + payloadOutletNo + "）与订单出水口（" + outletNo + "）不一致";
        }
        String stateMismatch = commandStateMismatch(orderStatus, orderFinishTime, afterSaleConfirmed, cmd);
        if (StrUtil.isNotBlank(stateMismatch)) {
            return stateMismatch;
        }

        // 失败/超时可能发生在未出水阶段，因此双侧均无实际量时允许；一旦任一侧提供实际量，
        // 两侧必须同时提供严格非负整数且数值一致，避免异常单拼接到其他执行结果。
        if (ObjectUtil.isNotNull(orderStatus) && orderStatus == TradeEnum.OrderStatus.ABNORMAL.getValue()) {
            String actualMismatch = abnormalActualMismatch(actualMl, cmd);
            if (StrUtil.isNotBlank(actualMismatch)) {
                return actualMismatch;
            }
        }

        // 完成/部分退款态核对实际水量，防「正确单号 + 错误水量」仍显示 ok。
        if (ObjectUtil.isNotNull(orderStatus)
                && (orderStatus == TradeEnum.OrderStatus.FINISHED.getValue()
                    || orderStatus == TradeEnum.OrderStatus.PART_REFUNDED.getValue())) {
            if (ObjectUtil.isNull(actualMl)) {
                return "订单已结算但缺少实际水量（ACTUAL_ML），履约证据不完整";
            }
            if (actualMl <= 0) {
                return "订单已结算但实际水量（ACTUAL_ML）不是正整数，履约证据不合法";
            }
            Long resultActualMl = resultActualMl(cmd);
            if (ObjectUtil.isNull(resultActualMl)) {
                return "订单已结算但指令结果报文缺少实际水量（actualMl），履约证据不完整";
            }
            if (resultActualMl <= 0) {
                return "订单已结算但指令结果实际水量（actualMl）不是正整数";
            }
            if (!resultActualMl.equals(actualMl)) {
                return "指令结果实际水量（" + resultActualMl + "）与订单实际水量（" + actualMl + "）不一致";
            }
        }
        if (ObjectUtil.isNotNull(orderStatus) && orderStatus == TradeEnum.OrderStatus.REFUNDED.getValue()) {
            Long resultActualMl = resultActualMl(cmd);
            if (ObjectUtil.isNull(actualMl) || actualMl != 0L
                    || ObjectUtil.isNull(resultActualMl) || resultActualMl != 0L) {
                return "零出水退款订单必须由实际水量为 0 的指令结果佐证";
            }
        }
        return null;
    }

    /**
     * 只接受当前真实状态机能够产生的订单/指令状态与时间证据组合。
     *
     * <p><b>4已完成 / 7已退款 的两种合法来源</b>：
     * ① 设备正常上报成功结果后由结算路径推进——指令必为 4执行成功；
     * ② <b>取水异常核账确认</b>（E2E-04 包A，{@code WaterAbnormalReconcileTxServiceImpl.confirm}）
     * 把 6异常待补偿 推进到 4 或 7，而它<b>刻意不改写指令</b>——指令是本次核账所依据的证据，
     * 改写证据去迎合结论正是核账要防的事。于是这类订单的指令永远停在 5执行失败 / 6超时。
     * 若矩阵只认「4/7/8 ↔ 指令 4」，售后一上线，PC 追溯会把每一张已核账订单都报成 mismatch。</p>
     *
     * <p>放宽仅限这一格：{@code afterSaleConfirmed} 为真且订单落在 4/7 时，额外接受
     * 5/6 终态指令（时间要求沿用 6异常待补偿 那一格的口径：必须有指令完成时间）。
     * 订单完成时间、8部分退款、以及后续的实际水量逐条核对一律不动——核账只改变了订单状态，
     * 没有、也不应该改变任何其他证据要求。</p>
     *
     * @param orderStatus        订单状态(1341)
     * @param orderFinishTime    订单完成时间
     * @param afterSaleConfirmed 该订单是否已经过取水异常核账确认（由调用方在批量查订单时一并带出，
     *                           不在本类内查库，避免逐行 N+1）
     * @param cmd                指令行（非 null）
     * @return null 表示组合合法；否则返回不一致原因
     */
    public static String commandStateMismatch(Integer orderStatus, String orderFinishTime,
                                              boolean afterSaleConfirmed, WsCommand cmd) {
        Integer cmdStatus = cmd.getCmdStatus();
        if (ObjectUtil.isNull(orderStatus)) {
            return "订单状态为空，无法核验指令履约阶段";
        }
        if (ObjectUtil.isNotNull(cmdStatus)) {
            if (cmdStatus == DeviceEnum.CmdStatus.PENDING.getValue()
                    && (StrUtil.isNotBlank(cmd.getSentTime())
                        || StrUtil.isNotBlank(cmd.getAckTime())
                        || StrUtil.isNotBlank(cmd.getFinishTime())
                        || StrUtil.isNotBlank(cmd.getResultPayload()))) {
                return "待下发指令不得携带下发、回执、完成时间或执行结果";
            }
            if (cmdStatus == DeviceEnum.CmdStatus.SENT.getValue()) {
                if (StrUtil.isBlank(cmd.getSentTime())) {
                    return "已下发指令缺少下发时间";
                }
                if (StrUtil.isNotBlank(cmd.getAckTime())
                        || StrUtil.isNotBlank(cmd.getFinishTime())
                        || StrUtil.isNotBlank(cmd.getResultPayload())) {
                    return "已下发指令不得提前携带回执、完成时间或执行结果";
                }
            }
            if (cmdStatus == DeviceEnum.CmdStatus.ACKED.getValue()) {
                if (StrUtil.isBlank(cmd.getSentTime()) || StrUtil.isBlank(cmd.getAckTime())) {
                    return "已回执指令必须具备下发和回执时间";
                }
                if (StrUtil.isNotBlank(cmd.getFinishTime()) || StrUtil.isNotBlank(cmd.getResultPayload())) {
                    return "已回执指令不得提前携带完成时间或执行结果";
                }
            }
        }
        if (orderStatus == TradeEnum.OrderStatus.PAID.getValue() && ObjectUtil.isNotNull(cmdStatus)
                && (cmdStatus == DeviceEnum.CmdStatus.PENDING.getValue()
                    || cmdStatus == DeviceEnum.CmdStatus.SENT.getValue())) {
            return null;
        }
        if (orderStatus == TradeEnum.OrderStatus.DISPENSING.getValue()) {
            if (ObjectUtil.isNull(cmdStatus) || cmdStatus != DeviceEnum.CmdStatus.ACKED.getValue()
                    || StrUtil.isBlank(cmd.getSentTime()) || StrUtil.isBlank(cmd.getAckTime())) {
                return "出水中订单必须关联已回执指令，并具备下发/回执时间";
            }
            return null;
        }
        if (orderStatus == TradeEnum.OrderStatus.FINISHED.getValue()
                || orderStatus == TradeEnum.OrderStatus.REFUNDED.getValue()
                || orderStatus == TradeEnum.OrderStatus.PART_REFUNDED.getValue()) {
            // 设备协议允许未先上报 ACK 而直接返回 result，因此成功终态不强制 ACK_TIME。
            boolean successShape = ObjectUtil.isNotNull(cmdStatus)
                    && cmdStatus == DeviceEnum.CmdStatus.SUCCESS.getValue()
                    && StrUtil.isNotBlank(cmd.getSentTime()) && StrUtil.isNotBlank(cmd.getFinishTime());
            // 8部分退款 不在放宽范围内：核账只会把 6 推进到 4 或 7（见 targetStatus 派生），
            // 把 8 一并放开等于凭空承认一条状态机产生不了的组合
            boolean reconciledShape = afterSaleConfirmed
                    && orderStatus != TradeEnum.OrderStatus.PART_REFUNDED.getValue()
                    && ObjectUtil.isNotNull(cmdStatus)
                    && (cmdStatus == DeviceEnum.CmdStatus.FAILED.getValue()
                        || cmdStatus == DeviceEnum.CmdStatus.TIMEOUT.getValue())
                    && StrUtil.isNotBlank(cmd.getFinishTime());
            if (!successShape && !reconciledShape) {
                return "已结算订单必须关联执行成功指令并具备下发/完成时间"
                        + "（经取水异常核账确认的订单可关联失败/超时终态指令，须具备完成时间）";
            }
            if (StrUtil.isBlank(orderFinishTime)) {
                return "已结算订单缺少订单完成时间，无法与指令终态相互佐证";
            }
            return null;
        }
        if (orderStatus == TradeEnum.OrderStatus.ABNORMAL.getValue()) {
            if (ObjectUtil.isNull(cmdStatus)
                    || (cmdStatus != DeviceEnum.CmdStatus.FAILED.getValue()
                        && cmdStatus != DeviceEnum.CmdStatus.TIMEOUT.getValue())
                    || StrUtil.isBlank(cmd.getFinishTime())) {
                return "异常待补偿订单必须关联失败/超时终态指令，并具备完成时间";
            }
            return null;
        }
        return "订单状态（" + orderStatus + "）与指令状态（" + cmdStatus + "）不属于允许的履约证据组合";
    }

    /**
     * 异常订单实际量证据：双侧均无值允许；任一侧有值时必须双侧严格非负且相等。
     *
     * @param orderActualMl 订单实际水量(毫升)，可为 null
     * @param cmd           指令行（非 null）
     */
    public static String abnormalActualMismatch(Long orderActualMl, WsCommand cmd) {
        boolean resultActualPresent = false;
        Long resultActualMl = null;
        if (StrUtil.isNotBlank(cmd.getResultPayload())) {
            JSONObject resultJson;
            try {
                resultJson = JSONUtil.parseObj(cmd.getResultPayload());
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
    public static Long strictJsonInteger(Object value) {
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

    /** 指令结果报文里的实际水量；报文缺失或畸形返回 null，由调用方按证据缺失 fail-closed。 */
    private static Long resultActualMl(WsCommand cmd) {
        if (StrUtil.isBlank(cmd.getResultPayload())) {
            return null;
        }
        try {
            return strictJsonInteger(JSONUtil.parseObj(cmd.getResultPayload()).get("actualMl"));
        } catch (Exception ignored) {
            return null;
        }
    }
}
