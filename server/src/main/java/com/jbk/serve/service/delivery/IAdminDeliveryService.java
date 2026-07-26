package com.jbk.serve.service.delivery;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.delivery.bo.AdminDeliveryAppealBo;
import com.jbk.tool.data.delivery.bo.AdminDeliveryTaskBo;
import com.jbk.tool.data.delivery.bo.DeliveryAppealDecideBo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryAppealEvidenceVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryAppealItemVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryOrderTraceVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryTaskDetailVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryTaskItemVo;
import com.jbk.tool.data.trade.vo.AdminOrderItemVo;

/**
 * PC 管理端配送域服务（E2E-03 包C）。
 *
 * <p>查询全部只读：任务/申诉/证据/追溯聚合不产生任何状态、资金或媒体副作用；
 * 唯一写入口是裁决，且完全委托包A {@link IDeliveryAppealTxService#decideAppeal}
 * 的裁决事务（只允许 3不成立驳回/5补送待执行/2成立待补偿，绝不写退款）。
 * 管理端可见全量数据（DRIVER_MANAGE 会话，与小程序 KH_USER 本人范围区开，铁律6）。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
public interface IAdminDeliveryService {

    /** 配送任务分页（订单+任务 join：用户/配送员/水种/费用/状态/预约时间）。 */
    PageDataVo<AdminDeliveryTaskItemVo> pageTasks(AdminDeliveryTaskBo bo);

    /** 任务详情（三照媒体元数据/异常/时间线；共键或状态矩阵不一致时 fail-closed）。 */
    AdminDeliveryTaskDetailVo taskDetail(Long taskId);

    /** 申诉分页（待处理排前）。 */
    PageDataVo<AdminDeliveryAppealItemVo> pageAppeals(AdminDeliveryAppealBo bo);

    /** 申诉证据详情（用户申诉材料 + 配送员举证 + 关联履约任务）。 */
    AdminDeliveryAppealEvidenceVo appealEvidence(Long appealId);

    /**
     * 裁决提交：透传包A 裁决事务，本层不重复实现任何状态/资金规则。
     *
     * @param adminUserId 当前后台会话员工ID（写入 HANDLE_BY）
     */
    boolean decideAppeal(DeliveryAppealDecideBo bo, Long adminUserId);

    /** 配送订单追溯聚合（delivery/appeals/auditEvents 三区块，供订单追溯装配）。 */
    AdminDeliveryOrderTraceVo buildOrderTrace(AdminOrderItemVo order);
}
