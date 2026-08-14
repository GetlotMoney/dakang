package com.jbk.serve.service.device;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.bo.WsCommandBatchBo;
import com.jbk.tool.data.device.vo.DeviceControlPreviewVo;
import com.jbk.tool.data.device.vo.WsCommandBatchVo;

/**
 * 高风险设备控制服务（E2E-05 包B，任务书 3.4/3.5）：批量控制 + 紧急停止，统一两步确认。
 * {@link #preview} 服务端按范围重新解析目标（绝不信前端集合），冻结目标与参数进 Redis，
 * 返回摘要与短时效一次性 operationTicket；{@link #confirm} Redis GETDEL 原子领取——
 * 过期/重复/换人/摘要不一致全部拒绝，领取成功才建批次展开子指令。紧急停止建模为
 * 单设备特殊批次（total=1）：preview 锚定活动出水订单（无活动链直接拒绝），
 * confirm 复验仍在出水中，停止指令始终关联真实订单（任务书 3.4）。
 *
 * @author dakang
 * @since 2026-07-30
 */
public interface IDeviceControlService {

    /** 第一步：解析目标 + 冻结参数 + 发放一次性操作凭据。 */
    DeviceControlPreviewVo preview(WsCommandBatchBo bo, Long operatorId);

    /** 第二步：领取凭据（GETDEL）→ 建批次 → 逐设备展开子指令。返回批次ID。 */
    Long confirm(WsCommandBatchBo bo, Long operatorId);

    PageDataVo<WsCommandBatchVo> pageData(WsCommandBatchBo bo);

    /** 批次详情（含子指令明细与设备编号）。 */
    WsCommandBatchVo getData(Long id);
}
