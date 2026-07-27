package com.jbk.tool.data.delivery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 管理端申诉证据详情 Vo（E2E-03 包C：用户申诉材料 + 配送员举证 + 关联履约任务）。
 * <p>linkStatus 核验申诉-任务-订单三方共键（与包A 裁决前置校验同口径）；
 * mismatch 时不下发任务详情与举证正向内容，裁决入口由前端按此禁用，
 * 后端 decideAppeal 仍会独立再查（双保险，不依赖前端）。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Schema(name = "AdminDeliveryAppealEvidenceVo", description = "管理端申诉证据详情")
public class AdminDeliveryAppealEvidenceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "申诉主体（含用户与处理人投影）")
    private AdminDeliveryAppealItemVo appeal;

    @Schema(description = "申诉-任务-订单共键核验：ok 一致 / mismatch 数据异常")
    private String linkStatus;

    @Schema(description = "linkStatus=mismatch 时的原因说明")
    private String linkReason;

    /**
     * D-215 申诉往来：同一 TASK_ID 下全部申诉按申诉时间正序（含 appeal 本身），
     * 让运营在裁决前看到上一轮的驳回理由与裁决人。
     * <p>linkStatus=mismatch 时同样不下发——被查记录已断链，不借历史扩大暴露面。
     * 历史条目只出文本与裁决结论，媒体元数据仍只针对当前 appealId 那条申诉，
     * 避免抽屉体量与受控媒体权限面随申诉轮次线性膨胀。</p>
     */
    @Schema(description = "同任务申诉往来（按申诉时间正序；mismatch 时为空）")
    private List<AdminDeliveryAppealItemVo> appealHistory;

    @Schema(description = "用户申诉举证媒体元数据")
    private List<AdminMediaRefVo> appealPhotos;

    @Schema(description = "配送员举证列表（按时间非递减）")
    private List<AdminCourierEvidenceVo> courierEvidences;

    @Schema(description = "关联配送任务详情（含履约核验、时间线与三照元数据；mismatch 时为空）")
    private AdminDeliveryTaskDetailVo task;
}
