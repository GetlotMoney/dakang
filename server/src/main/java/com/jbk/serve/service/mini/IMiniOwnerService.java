package com.jbk.serve.service.mini;

import com.jbk.tool.data.mini.bo.MiniOwnerBo;
import com.jbk.tool.data.mini.vo.MiniOwnerDeviceVo;
import com.jbk.tool.data.mini.vo.MiniOwnerServiceVo;

import java.util.List;

/**
 * 小程序机主域服务（E2E-05 包E）。数据范围铁律：一切查询与动作都以会话 userId 对
 * ws_device.OWNER_USER_ID 过滤/校验，绝不相信前端传入的归属声明（铁律6）。
 *
 * <p>只覆盖 E2E-05 必需能力：本人设备列表/详情（含遥测摘要）、维修与配件申报、
 * 本人申请列表与详情轨迹。经营收益属 E2E-06、消息中心属 E2E-07，这里不提供。</p>
 *
 * @author dakang
 * @since 2026-07-30
 */
public interface IMiniOwnerService {

    /** 本人名下设备列表（摘要段）。 */
    List<MiniOwnerDeviceVo> listDevices(Long userId);

    /** 本人名下设备详情（含最近遥测摘要与出水口）；非本人设备按不存在处理。 */
    MiniOwnerDeviceVo deviceDetail(String deviceNo, Long userId);

    /** 维修/配件申报：requestId 幂等，落真实 ws_work_order（入口待确认）。 */
    MiniOwnerServiceVo applyService(MiniOwnerBo bo, Long userId);

    /** 本人服务申请列表（APPLICANT_USER_ID 过滤）。 */
    List<MiniOwnerServiceVo> listServiceRequests(Long userId);

    /** 本人服务申请详情（含处理轨迹）；非本人申请按不存在处理。 */
    MiniOwnerServiceVo serviceDetail(String requestId, Long userId);

    // ==================== E2E-06 经营数据（订单口径毛额，纯只读） ====================

    /**
     * 经营概览：设备计数按名下设备；订单聚合范围=（站∈名下站 OR 设备∈名下设备）去重，
     * 排除待支付/已取消，充值单永不计入；周期缺省近 7 自然日含今日。
     * 无任何归属直接拒绝（沿 OWNER_SCOPE_DENIED 语义）。
     */
    com.jbk.tool.data.mini.vo.MiniOwnerOverviewVo overview(Long userId);

    /**
     * 交易快照分页：同一双轨范围与计入口径；deviceNo 筛选必须在名下（越权按
     * DEVICE_ACCESS_DENIED 语义拒绝且不泄露存在性）；分页非法拒绝，size 上限 100。
     * 明细不含任何下单用户身份字段（D-212）。
     */
    com.jbk.tool.data.PageDataVo<com.jbk.tool.data.mini.vo.MiniOwnerTransactionVo> transactionPage(
            com.jbk.tool.data.mini.bo.MiniOwnerTransactionBo bo, Long userId);
}
