package com.jbk.tool.data.settlement.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 分润 V2 整版计划发布入参（D-412/D-428 六方口径）：六个比例一次给齐、整版校验、
 * 整版生效——不存在"先发一半"的路径。全部万分比。
 *
 * @author dakang
 * @since 2026-08-14
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "SplitPlanBo", description = "分润V2整版计划发布入参（万分比）")
public class SplitPlanBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "售水线·水站(机主)比例")
    private Integer waterOwnerBp;

    @Schema(description = "售水线·商务推广(一级推荐)比例")
    private Integer waterReferrerBp;

    @Schema(description = "售水线·运营中心省级累计上限")
    private Integer regionProvinceCumBp;

    @Schema(description = "售水线·运营中心市级累计上限（省≥市≥区县）")
    private Integer regionCityCumBp;

    @Schema(description = "售水线·运营中心区县级累计上限")
    private Integer regionCountyCumBp;

    @Schema(description = "配送费线·配送员比例")
    private Integer deliveryCourierBp;

    @Schema(description = "生效时间（yyyyMMddHHmmss，缺省=当下；不允许过去时点）")
    private String effectTime;

    @Schema(description = "计划ID（查计划项用）")
    private Long planId;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "页码（≥1，缺省 1）")
    private Long current;

    @Schema(description = "页大小（1~100，缺省 20）")
    private Long size;

    public long pageOrDefault() {
        long value = current == null ? 1L : current;
        if (value <= 0 || value > 100_000) {
            throw new com.jbk.tool.exception.JbkException("页码不合法");
        }
        return value;
    }

    public long sizeOrDefault() {
        long value = size == null ? 20L : size;
        if (value <= 0 || value > 100) {
            throw new com.jbk.tool.exception.JbkException("页大小不合法");
        }
        return value;
    }
}
