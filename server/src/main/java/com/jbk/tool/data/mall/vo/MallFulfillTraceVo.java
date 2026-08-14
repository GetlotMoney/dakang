package com.jbk.tool.data.mall.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 商城履约轨迹节点 Vo（E2E-09 S3）。
 *
 * @author dakang
 * @since 2026-08-09
 */
@Data
@Accessors(chain = true)
public class MallFulfillTraceVo {

    @Schema(description = "到达节点(1396)")
    private Integer traceNode;

    @Schema(description = "节点名称")
    private String traceNodeName;

    @Schema(description = "操作方(1397)")
    private Integer actorType;

    @Schema(description = "操作方名称")
    private String actorTypeName;

    @Schema(description = "节点时间")
    private String traceTime;

    @Schema(description = "节点文案")
    private String traceText;
}
