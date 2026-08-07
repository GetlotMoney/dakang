package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 配送地址保存入参（新增/编辑同口径：编辑必须重填完整号码，服务端不回吐原号）。
 *
 * @author dakang
 * @since 2026-08-02
 */
@Data
@Schema(name = "MiniAddressSaveBo", description = "配送地址保存入参")
public class MiniAddressSaveBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "地址ID；缺省为新增")
    private Long addressId;

    @NotBlank(message = "联系人不能为空")
    @Size(max = 30, message = "联系人长度不能超过30")
    @Schema(description = "联系人姓名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String contactName;

    @NotBlank(message = "联系电话不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "请填写 11 位有效手机号")
    @Schema(description = "联系电话（11位；服务端只存不回吐）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;

    @NotBlank(message = "省市区不能为空")
    @Size(max = 100, message = "省市区长度不能超过100")
    @Schema(description = "省市区", requiredMode = Schema.RequiredMode.REQUIRED)
    private String region;

    @NotBlank(message = "详细地址不能为空")
    @Size(max = 200, message = "详细地址长度不能超过200")
    @Schema(description = "详细地址", requiredMode = Schema.RequiredMode.REQUIRED)
    private String detail;

    @Schema(description = "设为默认地址")
    private Boolean isDefault;

    @Schema(description = "是否已授权定位取点")
    private Boolean locationAuthorized;
}
