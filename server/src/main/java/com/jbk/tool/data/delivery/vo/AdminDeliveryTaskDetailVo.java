package com.jbk.tool.data.delivery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 管理端配送任务详情 Vo（E2E-03 包C）。
 * <p>fail-closed：linkStatus=mismatch（任务-订单共键错位/状态时间矩阵非法/总额恒等式破坏）时
 * 只保留任务与订单标识 + 异常原因，配送员、费用、时间线、三照等正向履约证据全部不下发，
 * 与订单追溯的指令/充值区块同一口径，不得拼凑展示。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "AdminDeliveryTaskDetailVo", description = "管理端配送任务详情")
public class AdminDeliveryTaskDetailVo extends AdminDeliveryTaskItemVo {

    private static final long serialVersionUID = 1L;

    @Schema(description = "任务-订单关联核验：ok 一致 / mismatch 数据异常（隐藏正向证据）")
    private String linkStatus;

    @Schema(description = "linkStatus=mismatch 时的原因说明")
    private String linkReason;

    @Schema(description = "履约时间线（下单→接单→离站→送达→三照签收）")
    private List<AdminDeliveryTimelineNodeVo> timeline;

    @Schema(description = "签收三照元数据（含受控媒体核验状态）")
    private List<AdminSignPhotoMetaVo> signPhotos;

    @Schema(description = "配送异常记录")
    private List<AdminDeliveryExceptionVo> exceptions;
}
