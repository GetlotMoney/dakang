package com.jbk.serve.service.delivery;

import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.exception.JbkException;

/**
 * 配送任务状态机转换矩阵（E2E-03 规则10）：
 * 1待接单 →(接单) 2已接单 →(离站) 3配送中 →(送达) 4已送达待确认 →(三照签收) 5已签收；
 * 5 →(用户申诉) 7申诉中 →(裁决) 5；1 →(用户待接单取消) 6已取消。其余一律非法。
 *
 * <p>纯函数，供 Service 与单测共用；Service 侧还必须叠加
 * 版本/配送员归属/订单共键条件（规则11），本类只钉状态维度。</p>
 */
public final class DeliveryTransitions {

    private DeliveryTransitions() {
    }

    /** 配送员推进动作（离站/送达）的目标态 → 唯一合法当前态；非法目标直接拒绝。 */
    public static int requireAdvanceSource(int targetStatus) {
        if (targetStatus == DeliveryEnum.TaskStatus.DELIVERING.getValue()) {
            return DeliveryEnum.TaskStatus.ACCEPTED.getValue();
        }
        if (targetStatus == DeliveryEnum.TaskStatus.ARRIVED.getValue()) {
            return DeliveryEnum.TaskStatus.DELIVERING.getValue();
        }
        throw new JbkException("配送任务状态不允许该操作");
    }

    /** 状态维度的转换合法性判定（含接单/签收/申诉/裁决），供单测全矩阵校验。 */
    public static boolean allowed(int from, int to) {
        int pending = DeliveryEnum.TaskStatus.PENDING.getValue();
        int accepted = DeliveryEnum.TaskStatus.ACCEPTED.getValue();
        int delivering = DeliveryEnum.TaskStatus.DELIVERING.getValue();
        int arrived = DeliveryEnum.TaskStatus.ARRIVED.getValue();
        int signed = DeliveryEnum.TaskStatus.SIGNED.getValue();
        int cancelled = DeliveryEnum.TaskStatus.CANCELLED.getValue();
        int appealing = DeliveryEnum.TaskStatus.APPEALING.getValue();
        if (from == pending && to == accepted) {
            return true;
        }
        // 待接单取消（E2E-04 包A）：只有尚未被任何配送员认领的任务可取消。
        // 2已接单 之后配送员已投入履约成本，取消要走异常/申诉链路，不是本边——
        // 这条边一旦放宽到 2/3/4，用户就能在配送员出发后单方面撤单并全额退款。
        if (from == pending && to == cancelled) {
            return true;
        }
        if (from == accepted && to == delivering) {
            return true;
        }
        if (from == delivering && to == arrived) {
            return true;
        }
        if (from == arrived && to == signed) {
            return true;
        }
        // 申诉：仅已签收可转申诉中；裁决：申诉中回到已签收（订单保持已完成态）
        if (from == signed && to == appealing) {
            return true;
        }
        return from == appealing && to == signed;
    }
}
