package com.jbk.tool.data.mall.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 商城前置仓 Po（E2E-09 S1）：电话原文不进小程序响应，展示恒脱敏。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_warehouse")
public class WsMallWarehouse extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "前置仓业务编号：唯一，不复用")
    @TableField("WAREHOUSE_NO")
    private String warehouseNo;

    @Schema(description = "前置仓名称")
    @TableField("WAREHOUSE_NAME")
    private String warehouseName;

    @Schema(description = "联系人")
    @TableField("CONTACT_NAME")
    private String contactName;

    @Schema(description = "联系电话：展示恒脱敏，原文不下发端上")
    @TableField("CONTACT_PHONE")
    private String contactPhone;

    @Schema(description = "省行政区码(6位)")
    @TableField("PROVINCE_CODE")
    private String provinceCode;

    @Schema(description = "市行政区码(6位)")
    @TableField("CITY_CODE")
    private String cityCode;

    @Schema(description = "区行政区码(6位)")
    @TableField("DISTRICT_CODE")
    private String districtCode;

    @Schema(description = "详细地址")
    @TableField("WAREHOUSE_ADDRESS")
    private String warehouseAddress;

    @Schema(description = "经度（字符串合同精度）")
    @TableField("LONGITUDE")
    private String longitude;

    @Schema(description = "纬度")
    @TableField("LATITUDE")
    private String latitude;

    @Schema(description = "履约范围 JSON（districts 型行政区码集），服务端唯一校验")
    @TableField("SERVICE_SCOPE_JSON")
    private String serviceScopeJson;

    @Schema(description = "前置仓状态(1390)：1启用 2停用；停用不参与可售聚合")
    @TableField("WAREHOUSE_STATUS")
    private Integer warehouseStatus;

    @Schema(description = "乐观锁版本")
    @TableField("VERSION")
    private Integer version;
}
