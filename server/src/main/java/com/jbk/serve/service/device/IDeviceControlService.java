package com.jbk.serve.service.device;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.bo.WsCommandBatchBo;
import com.jbk.tool.data.device.vo.DeviceControlPreviewVo;
import com.jbk.tool.data.device.vo.WsCommandBatchVo;

/**
 * 高风险设备控制服务（E2E-05 包B，任务书 3.4/3.5）：批量控制 + 紧急停止，统一两步确认。
 *
 * <h3>两步确认协议</h3>
 * <ol>
 *   <li>{@link #preview}：服务端按范围重新解析目标（绝不信前端集合），冻结目标与参数
 *       进 Redis，返回目标数量、摘要与短时效一次性 operationTicket；</li>
 *   <li>{@link #confirm}：Redis GETDEL 原子领取 ticket——过期、重复、换人、
 *       摘要不一致（参数/目标变化）全部拒绝；领取成功才建批次并逐设备展开子指令。</li>
 * </ol>
 *
 * <p>紧急停止建模为「单设备、cmdType=2 停止出水」的特殊批次（total=1）：preview 阶段
 * 由服务端查出该设备当前活动出水订单（ORDER_STATUS=3）锚定 orderId，不存在活动出水链
 * 直接拒绝；confirm 阶段复验订单仍在出水中。停止指令因此始终关联真实订单，
 * 与订单链路触发的停止语义完全一致（任务书 3.4）。</p>
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
