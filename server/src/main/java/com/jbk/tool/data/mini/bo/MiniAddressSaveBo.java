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

    /**
     * 收货区县行政区码(6位)：商城选仓的唯一判据（E2E-09 S2）。
     *
     * <p>刻意可空且不设 @NotBlank：一期配送链不需要它，存量地址也没有；强制必填会
     * 让老用户改任何一个字段都被拦住。留空的地址在商城结算处 fail-closed 提示补选，
     * 而不是由服务端按 region 文本猜——猜错会把订单派给根本不覆盖该地址的前置仓。</p>
     */
    @Size(max = 6, message = "行政区码为 6 位")
    @Pattern(regexp = "^$|^\\d{6}$", message = "请选择所在区县")
    @Schema(description = "收货区县行政区码(6位)：商城下单必需，一期配送可留空")
    private String districtCode;

    @Schema(description = "设为默认地址")
    private Boolean isDefault;

    @Schema(description = "是否已授权定位取点")
    private Boolean locationAuthorized;
}
