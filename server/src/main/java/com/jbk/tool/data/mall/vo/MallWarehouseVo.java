package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 前置仓 Vo（E2E-09 S1）：联系电话恒脱敏（PhoneMask），原文不出接口。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MallWarehouseVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "前置仓ID（string）")
    private String id;

    @Schema(description = "前置仓业务编号")
    private String warehouseNo;

    @Schema(description = "前置仓名称")
    private String warehouseName;

    @Schema(description = "联系人")
    private String contactName;

    @Schema(description = "联系电话（脱敏）")
    private String maskedPhone;

    @Schema(description = "省行政区码")
    private String provinceCode;

    @Schema(description = "市行政区码")
    private String cityCode;

    @Schema(description = "区行政区码")
    private String districtCode;

    @Schema(description = "详细地址")
    private String warehouseAddress;

    @Schema(description = "经度")
    private String longitude;

    @Schema(description = "纬度")
    private String latitude;

    @Schema(description = "履约行政区码集（SERVICE_SCOPE_JSON 解析）")
    private List<String> districtCodes;

    @Schema(description = "前置仓状态(1390)")
    private Integer warehouseStatus;

    @Schema(description = "版本（启停 CAS 锚）")
    private Integer version;

    @Schema(description = "创建时间")
    private String createTime;
}
