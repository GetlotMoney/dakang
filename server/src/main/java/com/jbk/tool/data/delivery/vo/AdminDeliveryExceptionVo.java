package com.jbk.tool.data.delivery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 配送异常记录 Vo（ws_delivery_exception 只插入不更新；PC 只读呈现）。
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Accessors(chain = true)
@Schema(name = "AdminDeliveryExceptionVo", description = "配送异常记录")
public class AdminDeliveryExceptionVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "异常记录ID")
    private Long exceptionId;

    @Schema(description = "异常原因(1354)")
    private Integer reason;

    @Schema(description = "异常原因名称")
    private String reasonLabel;

    @Schema(description = "异常说明")
    private String description;

    @Schema(description = "举证媒体元数据")
    private List<AdminMediaRefVo> evidenceRefs;

    @Schema(description = "上报时间")
    private String createTime;
}
