package com.jbk.serve.service.ops;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.ops.bo.WorkOrderApplyBo;
import com.jbk.tool.data.ops.bo.WsWorkOrderBo;
import com.jbk.tool.data.ops.po.WsWorkOrder;
import com.jbk.tool.data.ops.vo.WsWorkOrderVo;

/**
 * 统一工单领域服务（E2E-05 包C）：告警转单、机主申报、PC 巡检共用一个状态机
 * （{@link WorkOrderTransitions} 单一出处），禁止任何调用方另写迁移逻辑。
 *
 * <p>全部动作：前态 CAS + VERSION 递增，影响行数必须为 1；关键状态变化在同一事务内
 * recordReliableOnceAs 落审计（fail-closed）。幂等：告警转单靠 uk_wo_alarm，
 * 机主申报靠 uk_wo_request——撞键读回原工单，绝不产生第二张。</p>
 *
 * @author dakang
 * @since 2026-07-30
 */
public interface IWorkOrderService extends IService<WsWorkOrder> {

    /** 告警转工单（PC）：仅活动告警可转；撞 uk_wo_alarm 幂等返回既有工单。回链告警状态。 */
    Long createFromAlarm(Long alarmId, Long operatorId);

    /** PC 建单（巡检/维修/配件）：直接进待分配。设备类工单校验设备档案存在。 */
    Long createInspection(WsWorkOrderBo bo, Long operatorId);

    /**
     * 机主服务申报（维修/配件）：入口待确认。归属：设备 OWNER_USER_ID 必须等于会话
     * userId（服务端强制，任务书包E：前端不得传 userId 决定归属）。requestId 幂等。
     * 证据媒体键以机主身份（OWNER_PORTAL=2）claim 绑定到工单。
     */
    Long ownerApply(WorkOrderApplyBo bo, Long userId);

    /** 确认（PC）：待确认 → 待分配。 */
    void confirm(Long id, Long operatorId);

    /** 驳回（PC）：待确认 → 已驳回，原因必填。 */
    void reject(Long id, String reason, Long operatorId);

    /** 分配（PC）：待分配 → 处理中，处理人必须是未停用的有效员工。 */
    void assign(Long id, Long assigneeId, Long operatorId);

    /** 提交处理结果（PC 处理人）：处理中 → 待复核，结果必填；证据以员工身份 claim。 */
    void submitResult(Long id, String result, java.util.List<String> resultPhotos, Long operatorId);

    /** 复核通过（PC）：待复核 → 已关闭；来源告警的活动键此刻释放（任务书 3.2）。 */
    void reviewPass(Long id, String remark, Long operatorId);

    /** 复核退回（PC）：待复核 → 处理中，意见必填。 */
    void reviewReturn(Long id, String remark, Long operatorId);

    /** PC 分页（全量数据，MANAGE 会话）。 */
    PageDataVo<WsWorkOrderVo> pageData(WsWorkOrderBo bo);

    /** PC 详情：含设备/水站/告警联查与完整状态轨迹。 */
    WsWorkOrderVo getData(Long id);

    /** 机主本人申报分页（服务端按 APPLICANT_USER_ID 过滤，铁律6）。 */
    PageDataVo<WsWorkOrderVo> pageByApplicant(WsWorkOrderBo bo, Long userId);

    /** 机主本人申报详情：非本人申报的工单按不存在处理（不泄露存在性）。 */
    WsWorkOrderVo getByApplicant(Long id, Long userId);
}
