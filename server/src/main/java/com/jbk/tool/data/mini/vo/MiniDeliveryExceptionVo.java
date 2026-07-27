package com.jbk.tool.data.mini.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 小程序配送异常记录 Vo（E2E-03 包B；对齐 miniapp delivery.ts DeliveryExceptionRecord）。
 * <p>reason 用契约字符码（UNREACHABLE/ADDRESS/QUANTITY/DAMAGED/OTHER），
 * 与 1354 整数值的映射只在服务端一处维护。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "MiniDeliveryExceptionVo", description = "小程序配送异常记录")
public class MiniDeliveryExceptionVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "异常记录ID")
    private Long exceptionId;

    @Schema(description = "任务号")
    private String taskNo;

    @Schema(description = "上报配送员ID")
    private Long courierId;

    @Schema(description = "异常原因码：UNREACHABLE/ADDRESS/QUANTITY/DAMAGED/OTHER")
    private String reason;

    @Schema(description = "异常说明")
    private String description;

    @Schema(description = "举证受控媒体键列表")
    private List<String> evidenceRefs;

    @Schema(description = "上报时间")
    private String createTime;
}
