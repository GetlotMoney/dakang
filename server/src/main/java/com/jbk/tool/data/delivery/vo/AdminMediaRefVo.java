package com.jbk.tool.data.delivery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 受控媒体引用元数据 Vo（PC 只呈现元数据，不回传图片字节；一期无媒体下载出口）。
 * <p>mediaStatus 由服务端按 ws_delivery_media 归属/用途/绑定关系核验：
 * ok=登记且绑定当前任务；missing=引用键无登记记录；invalid=登记存在但归属或绑定不符。
 * 非 ok 一律不提供 mime/大小等正向元数据，防止把断链引用拼成有效证据。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Accessors(chain = true)
@Schema(name = "AdminMediaRefVo", description = "受控媒体引用元数据")
public class AdminMediaRefVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "受控媒体键")
    private String mediaKey;

    @Schema(description = "MIME类型（核验通过才提供）")
    private String mimeType;

    @Schema(description = "内容字节数（核验通过才提供）")
    private Long sizeBytes;

    @Schema(description = "登记时间（核验通过才提供）")
    private String uploadTime;

    @Schema(description = "媒体核验状态：ok/missing/invalid")
    private String mediaStatus;

    @Schema(description = "非 ok 时的原因说明")
    private String mediaReason;
}
