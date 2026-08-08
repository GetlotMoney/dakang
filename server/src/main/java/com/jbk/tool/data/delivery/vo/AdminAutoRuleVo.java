package com.jbk.tool.data.delivery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 自动补货规则后台只读 Vo（S2 R1）：只下发 PC 页面实际使用的字段。
 * RULE_KEY（创建幂等键）与明文电话不出接口——键是内部防重材料，电话必须脱敏。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class AdminAutoRuleVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "规则ID")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "容器规格")
    private String containerSpec;

    @Schema(description = "每期配送数量")
    private Integer deliveryCount;

    @Schema(description = "周期间隔（天）")
    private Integer intervalDays;

    @Schema(description = "周期锚点(yyyyMMddHHmmss)")
    private String anchorTime;

    @Schema(description = "规则状态(1355)：1启用 2停用 3已取消")
    private Integer ruleStatus;

    @Schema(description = "收货地址")
    private String receiveAddress;

    @Schema(description = "收货电话（脱敏）")
    private String maskedPhone;

    @Schema(description = "创建时间")
    private String createTime;
}
