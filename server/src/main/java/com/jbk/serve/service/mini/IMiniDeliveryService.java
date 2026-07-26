package com.jbk.serve.service.mini;

import com.jbk.tool.data.delivery.bo.DeliveryAppealCreateBo;
import com.jbk.tool.data.delivery.bo.DeliveryAppealEvidenceBo;
import com.jbk.tool.data.delivery.bo.DeliveryCreateBo;
import com.jbk.tool.data.delivery.bo.DeliveryExceptionReportBo;
import com.jbk.tool.data.delivery.bo.DeliverySignBo;
import com.jbk.tool.data.mini.bo.MiniDeliveryMediaUploadBo;
import com.jbk.tool.data.mini.vo.MiniCourierAdmissionVo;
import com.jbk.tool.data.mini.vo.MiniDeliveryAppealVo;
import com.jbk.tool.data.mini.vo.MiniDeliveryCreateVo;
import com.jbk.tool.data.mini.vo.MiniDeliveryExceptionVo;
import com.jbk.tool.data.mini.vo.MiniDeliveryMediaVo;
import com.jbk.tool.data.mini.vo.MiniDeliveryTaskVo;

import java.util.List;

/**
 * 小程序配送域读模型与编排入口（E2E-03 包B）。
 *
 * <p>职责边界：Vo 装配 + 委托包A 服务（{@code serve/service/delivery}）。状态机、金额、
 * 幂等、时间、数据范围全部由包A 事务服务与 SQL 条件裁决，本层不复制第二套判定；
 * userId 一律来自会话（铁律6），配送员身份/范围由 CourierAccess 按登录人解析（铁律7）。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
public interface IMiniDeliveryService {

    /** 用户创建配送订单（幂等：同 userId+requestId 恒返回同一订单与任务）。 */
    MiniDeliveryCreateVo createOrder(DeliveryCreateBo bo, Long userId);

    /** 配送员任务列表：available 可接池（包A SQL 含范围/自配送/可履约过滤）/ active 本人进行中 / history 本人终态。 */
    List<MiniDeliveryTaskVo> pageTasks(String view, Long userId);

    /** 配送员任务详情（本人任务任意态可见；未分配任务须 在范围+非本人下单+订单可履约 才可见）。 */
    MiniDeliveryTaskVo taskDetail(String taskNo, Long userId);

    /** 接单（委托包A CAS 条件 UPDATE）。 */
    MiniDeliveryTaskVo acceptTask(String taskNo, Integer expectedVersion, Long userId);

    /** 推进：3离站 / 4送达（合法当前态由 DeliveryTransitions 裁决）。 */
    MiniDeliveryTaskVo advanceTask(String taskNo, int targetStatus, Integer expectedVersion, Long userId);

    /** 三照签收（媒体键在事务内原子占用；签收即订单完成）。 */
    MiniDeliveryTaskVo signTask(DeliverySignBo bo, Long userId);

    /** 配送异常上报（不推进状态；举证媒体键原子占用）。 */
    MiniDeliveryExceptionVo reportException(DeliveryExceptionReportBo bo, Long userId);

    /** 配送员查看本人任务的异常记录（未分配/他人任务 fail-closed）。 */
    List<MiniDeliveryExceptionVo> listTaskExceptions(String taskNo, Long userId);

    /** 配送员查看本人任务的申诉（无申诉返回 null）。 */
    MiniDeliveryAppealVo getTaskAppeal(String taskNo, Long userId);

    /** 配送员追加申诉举证（限任务归属配送员、任务申诉中）。 */
    MiniDeliveryAppealVo appendAppealEvidence(DeliveryAppealEvidenceBo bo, Long userId);

    /** 配送准入状态（无记录返回 status=0 未提交；准入建档/审核属 PC B09，小程序只读）。 */
    MiniCourierAdmissionVo getAdmission(Long userId);

    /** 受控媒体登记：校验 base64/类型白名单后委托包A register，返回媒体键。 */
    MiniDeliveryMediaVo uploadMedia(MiniDeliveryMediaUploadBo bo, Long userId);

    /** 消费者视角：本人配送订单的任务证据（U06 轨迹/三照/实际数量/申诉截止）；无任务返回 null。 */
    MiniDeliveryTaskVo getMyDeliveryTask(String orderNo, Long userId);

    /** 消费者视角：本人申诉详情（非本人按不存在拒绝）。 */
    MiniDeliveryAppealVo getMyAppeal(String appealId, Long userId);

    /** 用户创建申诉（本人+订单已完成+任务已签收+24h 窗口，全部由包A 事务裁决）。 */
    MiniDeliveryAppealVo createAppeal(DeliveryAppealCreateBo bo, Long userId);
}
