package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 小程序配送媒体上传入参（E2E-03 A5 受控媒体：统一 POST+JSON 工程惯例，内容走 base64）。
 *
 * <p>大小/类型白名单的最终防线在 {@link com.jbk.serve.service.delivery.IDeliveryMediaService}
 * （≤10MB、仅 image/*）；Bo 只做入参形态界（base64 串长度上界≈10MB 原始内容的 4/3），
 * 防止超大字符串进入解码。</p>
 */
@Data
@Schema(name = "MiniDeliveryMediaUploadBo", description = "小程序配送媒体上传入参")
public class MiniDeliveryMediaUploadBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "purpose 不能为空")
    @Schema(description = "媒体用途：1签收三照 2申诉举证 3异常举证（跨用途引用会在提交时被拒）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer purpose;

    @NotBlank(message = "mimeType 不能为空")
    @Size(max = 50, message = "mimeType 不合法")
    @Schema(description = "MIME 类型，仅允许 image/*", requiredMode = Schema.RequiredMode.REQUIRED)
    private String mimeType;

    @NotBlank(message = "图片内容不能为空")
    @Size(max = 14_680_064, message = "图片内容超出大小限制")
    @Schema(description = "图片内容 base64（原始内容≤10MB）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String contentBase64;
}
