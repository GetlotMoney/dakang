package com.jbk.tool.data.delivery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 配送员申诉举证 Vo（ws_delivery_appeal.COURIER_EVIDENCES JSON 元素投影）。
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Accessors(chain = true)
@Schema(name = "AdminCourierEvidenceVo", description = "配送员申诉举证")
public class AdminCourierEvidenceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "举证说明")
    private String description;

    @Schema(description = "举证时间（统一逻辑时钟，非递减）")
    private String time;

    @Schema(description = "举证媒体元数据")
    private List<AdminMediaRefVo> evidenceRefs;
}
