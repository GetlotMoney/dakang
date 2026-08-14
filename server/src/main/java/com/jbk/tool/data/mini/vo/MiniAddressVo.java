package com.jbk.tool.data.mini.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 配送地址（小程序侧投影）。电话恒为 PhoneMask 脱敏值——原号不回流前端，
 * 编辑动线要求重填完整号码（页面占位符提示原号脱敏形态）。
 *
 * @author dakang
 * @since 2026-08-02
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "MiniAddressVo", description = "配送地址（电话恒脱敏）")
public class MiniAddressVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "地址ID（string，防精度丢失）")
    private String addressId;

    @Schema(description = "联系人姓名")
    private String contactName;

    @Schema(description = "联系电话（PhoneMask 脱敏）")
    private String maskedPhone;

    @Schema(description = "省市区")
    private String region;

    @Schema(description = "详细地址")
    private String detail;

    @Schema(description = "收货区县行政区码(6位)：为空表示尚未选择，商城下单前需补选")
    private String districtCode;

    @Schema(description = "是否默认地址")
    private Boolean isDefault;

    @Schema(description = "是否已授权定位取点")
    private Boolean locationAuthorized;
}
