package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 配送员自助准入申请 Bo（S3）。
 *
 * <p>申请主体恒取会话 userId，本 Bo 刻意不含 userId 字段——手机号只是联系信息，
 * 不构成身份切换或替他人申请的依据。资质照片、劳动合同等材料待甲方清单后追加。</p>
 *
 * @author dakang
 * @since 2026-08-07
 */
@Data
public class MiniAdmissionSubmitBo {

    @Schema(description = "申请人姓名")
    @NotBlank(message = "请填写姓名")
    @Size(max = 50, message = "姓名不能超过 50 字")
    private String applicantName;

    @Schema(description = "联系电话（仅联系用途）")
    @NotBlank(message = "请填写联系电话")
    @Pattern(regexp = "^1\\d{10}$", message = "请填写 11 位手机号")
    private String phone;

    @Schema(description = "申请服务水站ID集（与服务区域至少一项）")
    private List<Long> requestedStationIds;

    @Schema(description = "申请服务区域（与水站至少一项）")
    @Size(max = 100, message = "服务区域不能超过 100 字")
    private String requestedRegion;

    @Schema(description = "真实性与配送规范承诺")
    @NotNull(message = "请先勾选真实性与配送规范承诺")
    @AssertTrue(message = "请先勾选真实性与配送规范承诺")
    private Boolean declarationAccepted;
}
