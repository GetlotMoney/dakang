package com.jbk.tool.data.product.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 水卡套餐（L2-READ：充值链只读数据源）。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_package")
@Schema(name = "WsPackage", description = "水卡套餐")
public class WsPackage extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "套餐名称(max50)")
    @TableField("PACKAGE_NAME")
    private String packageName;

    @Schema(description = "售价(分)")
    @TableField("PAY_AMOUNT")
    private Long payAmount;

    @Schema(description = "兑换水量(毫升)，0=纯余额充值套餐")
    @TableField("WATER_ML")
    private Long waterMl;

    @Schema(description = "赠送余额(分)")
    @TableField("BONUS_AMOUNT")
    private Long bonusAmount;

    @Schema(description = "折算单价快照(分/升，字符串保留两位)，退款折算与对账依据")
    @TableField("UNIT_PRICE_SNAP")
    private String unitPriceSnap;

    @Schema(description = "可用范围JSON（空=未配置并默认拒绝；全场需显式 scopeType=all）")
    @TableField("SCOPE_JSON")
    private String scopeJson;

    @Schema(description = "有效期(天)，空=永久")
    @TableField("EXPIRE_DAYS")
    private Integer expireDays;

    @Schema(description = "状态(1330)：1在售 2下架")
    @TableField("PACKAGE_STATUS")
    private Integer packageStatus;

    @Schema(description = "备注(max500)")
    @TableField("PACKAGE_REMARK")
    private String packageRemark;
}
