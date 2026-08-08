package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 自动补货规则 Vo（小程序本人规则列表，S2）。
 *
 * @author dakang
 * @since 2026-08-07
 */
@Data
@Accessors(chain = true)
public class MiniAutoRuleVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "规则ID")
    private Long id;

    @Schema(description = "水种名称（关联 ws_water_type 派生）")
    private String waterTypeName;

    @Schema(description = "容器规格")
    private String containerSpec;

    @Schema(description = "每期配送数量")
    private Integer deliveryCount;

    @Schema(description = "收货地址")
    private String receiveAddress;

    @Schema(description = "周期间隔（天）")
    private Integer intervalDays;

    @Schema(description = "下次到期时间(yyyyMMddHHmmss)；停用/已取消为空")
    private String nextDueTime;

    @Schema(description = "规则状态(1355)：1启用 2停用 3已取消")
    private Integer ruleStatus;

    @Schema(description = "最近一次执行结果；从未执行过为空")
    private String lastResult;

    @Schema(description = "最近一次执行结果时间(yyyyMMddHHmmss)；从未执行过为空")
    private String lastResultTime;
}
