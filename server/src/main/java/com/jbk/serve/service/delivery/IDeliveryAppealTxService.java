package com.jbk.serve.service.delivery;

import com.jbk.tool.data.delivery.bo.DeliveryAppealCreateBo;
import com.jbk.tool.data.delivery.bo.DeliveryAppealDecideBo;
import com.jbk.tool.data.delivery.bo.DeliveryAppealEvidenceBo;
import com.jbk.tool.data.delivery.po.WsDeliveryAppeal;
import com.jbk.tool.data.delivery.po.WsDeliveryException;

import java.util.List;

/**
 * 配送申诉服务（E2E-03 A4 / 规则14~18）。
 *
 * <p>创建限订单本人且签收后 24h 内；配送员只见己任务举证；PC 裁决只产生
 * 3不成立驳回 / 5补送待执行 / 2成立待补偿 三种确定结果，资金补偿只落待处理态，
 * 绝不写退款成功（真实退款属 E2E-04）。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
public interface IDeliveryAppealTxService {

    /** 用户创建申诉（本人 + 订单已完成 + 任务已签收 + 无活动申诉 + 窗口 [signTime, +24h]）。 */
    WsDeliveryAppeal createAppeal(DeliveryAppealCreateBo bo, Long actorUserId, String now);

    /** 配送员追加举证（限任务归属配送员；任务须 7申诉中、订单已完成、申诉待处理）。 */
    WsDeliveryAppeal appendCourierEvidence(DeliveryAppealEvidenceBo bo, Long actorUserId, String now);

    /**
     * PC 裁决（E2E-04 包A：策略码驱动）。入参只有 strategyCode 一个真相源，申诉终态由
     * {@code AfterSaleStrategy.deriveOutcome} 派生；数量边界由 {@code requireApprovedCount} 判定；
     * 申诉转终态、任务 7→5，订单保持已完成。资金策略与 RESEND 在<b>同事务</b>登记一条待执行售后动作
     * （REJECT 不登记），实际返还由售后返还内核在独立事务执行——本方法仍然一分钱不动。
     */
    boolean decideAppeal(DeliveryAppealDecideBo bo, Long adminUserId, String now);

    /** 配送员查看本人任务的申诉（未分配/他人任务/共键错位 fail-closed；无申诉返回 null）。 */
    WsDeliveryAppeal getTaskAppealForCourier(String taskNo, Long actorUserId);

    /** 配送员查看本人任务的异常记录（同上访问收口；只含本人上报）。 */
    List<WsDeliveryException> listTaskExceptionsForCourier(String taskNo, Long actorUserId);
}
