package com.jbk.tool.data.user.po;

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
 * 水卡表 Po
 * <p>
 * 资金铁律：BALANCE_AMOUNT / BALANCE_ML 扣减必须原子 UPDATE + 流水，
 * 禁止读出-内存计算-写回；后台管理仅做状态变更，不直接改余额。
 * </p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_card")
@Schema(name = "WsCard", description = "水卡表")
public class WsCard extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "卡号(max32)，虚拟卡系统生成/实体卡取卡面编号")
    @TableField("CARD_NO")
    private String cardNo;

    @Schema(description = "卡类型(1331)：1虚拟卡 2实体卡")
    @TableField("CARD_TYPE")
    private Integer cardType;

    @Schema(description = "持卡用户ID（ws_user.ID，主卡人）")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "余额(分)")
    @TableField("BALANCE_AMOUNT")
    private Long balanceAmount;

    @Schema(description = "剩余水量(毫升)")
    @TableField("BALANCE_ML")
    private Long balanceMl;

    @Schema(description = "最近购买套餐ID")
    @TableField("PACKAGE_ID")
    private Long packageId;

    @Schema(description = "套餐快照JSON（名称/售价/水量/单价）")
    @TableField("PACKAGE_SNAP")
    private String packageSnap;

    @Schema(description = "可用范围JSON（授权范围模型预留）")
    @TableField("SCOPE_JSON")
    private String scopeJson;

    @Schema(description = "到期时间，空=永久")
    @TableField("EXPIRE_TIME")
    private String expireTime;

    @Schema(description = "卡状态(1332)：1正常 2冻结 3已过期 4已注销")
    @TableField("CARD_STATUS")
    private Integer cardStatus;

    @Schema(description = "备注(max500)")
    @TableField("CARD_REMARK")
    private String cardRemark;

    @Schema(description = "首次购卡发行订单ID（L2-A3 唯一发行锚点，uk_card_issue_order）；历史/实体/人工卡为 NULL")
    @TableField("ISSUE_ORDER_ID")
    private Long issueOrderId;
}
