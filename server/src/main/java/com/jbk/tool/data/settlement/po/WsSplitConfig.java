package com.jbk.tool.data.settlement.po;

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
 * 分账比例配置 Po（E2E-08 包A）。版本化：改比例=插新生效版本，历史版本只读；
 * 消费端取「订单创建时点生效版本」并把比例写入 SPLIT_RATE_SNAP，变更不追溯。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_split_config")
@Schema(name = "WsSplitConfig", description = "分账比例配置（版本化）")
public class WsSplitConfig extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "商品线(1376)：1售水 2配送")
    @TableField("PRODUCT_LINE")
    private Integer productLine;

    @Schema(description = "收款方类型(1377)：1机主 2配送员 3平台 4渠道(预留) 5推荐人(预留) 6区域服务商(预留)")
    @TableField("RECEIVER_TYPE")
    private Integer receiverType;

    @Schema(description = "比例（万分比，如 7000=70%；整除余数恒归平台）")
    @TableField("SPLIT_RATE")
    private Integer splitRate;

    @Schema(description = "生效时间（含）；取订单创建时点生效版本")
    @TableField("EFFECT_TIME")
    private String effectTime;

    @Schema(description = "备注")
    @TableField("CONFIG_REMARK")
    private String configRemark;
}
