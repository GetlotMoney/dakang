package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 前置仓维护 Bo（E2E-09 S1）：履约范围收结构化行政区码集，
 * SERVICE_SCOPE_JSON 由服务端唯一构造与校验。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
public class MallWarehouseBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "前置仓ID（update 必填）")
    private Long id;

    @Schema(description = "前置仓业务编号（save 必填；不复用不可改）")
    @Size(max = 50, message = "仓库编号不能超过 50 字")
    private String warehouseNo;

    @Schema(description = "前置仓名称")
    @NotBlank(message = "请填写仓库名称")
    @Size(max = 100, message = "仓库名称不能超过 100 字")
    private String warehouseName;

    @Schema(description = "联系人")
    @NotBlank(message = "请填写联系人")
    @Size(max = 50, message = "联系人不能超过 50 字")
    private String contactName;

    @Schema(description = "联系电话")
    @NotBlank(message = "请填写联系电话")
    @Size(max = 20, message = "联系电话不能超过 20 字")
    private String contactPhone;

    @Schema(description = "省行政区码（6位）")
    @NotBlank(message = "请选择省")
    @Pattern(regexp = "^\\d{6}$", message = "省行政区码必须为 6 位数字")
    private String provinceCode;

    @Schema(description = "市行政区码（6位）")
    @NotBlank(message = "请选择市")
    @Pattern(regexp = "^\\d{6}$", message = "市行政区码必须为 6 位数字")
    private String cityCode;

    @Schema(description = "区行政区码（6位）")
    @NotBlank(message = "请选择区")
    @Pattern(regexp = "^\\d{6}$", message = "区行政区码必须为 6 位数字")
    private String districtCode;

    @Schema(description = "详细地址")
    @NotBlank(message = "请填写详细地址")
    @Size(max = 200, message = "详细地址不能超过 200 字")
    private String warehouseAddress;

    @Schema(description = "经度")
    @Size(max = 20, message = "经度不能超过 20 字")
    private String longitude;

    @Schema(description = "纬度")
    @Size(max = 20, message = "纬度不能超过 20 字")
    private String latitude;

    @Schema(description = "履约行政区码集（服务端构造范围 JSON）")
    private List<String> districtCodes;
}
