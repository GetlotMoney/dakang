package com.jbk.tool.data.settlement.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 分润归属管理入参（机主推荐关系 / 区域归属链共用一 Bo，各端点只取所需并自行校验）。
 *
 * @author dakang
 * @since 2026-08-14
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "OwnerAttributionBo", description = "分润归属管理入参")
public class OwnerAttributionBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "机主用户ID")
    private Long ownerUserId;

    @Schema(description = "直接推荐人用户ID（推荐关系录入用）")
    private Long referrerUserId;

    @Schema(description = "省级运营中心用户ID（归属链录入用，可空）")
    private Long provinceAgentUserId;

    @Schema(description = "市级运营中心用户ID（可空）")
    private Long cityAgentUserId;

    @Schema(description = "区县级运营中心用户ID（可空）")
    private Long countyAgentUserId;

    @Schema(description = "归属来源：PRIVATE_REFERRAL 血缘 / PUBLIC_MANUAL 公域人工分配")
    private String attributionSource;

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
