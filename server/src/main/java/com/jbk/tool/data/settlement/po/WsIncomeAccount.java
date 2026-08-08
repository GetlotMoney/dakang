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
 * 收益账户 Po（E2E-08 / REQ-072）：分润余额，与水卡余额/用户余额物理隔离。
 * 余额变动一律走「前值+VERSION」双条件 CAS；BALANCE_FEN 恒等于末笔收益流水 AFTER（对账不变式）。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_income_account")
@Schema(name = "WsIncomeAccount", description = "收益账户（分润余额）")
public class WsIncomeAccount extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "收益人（ws_user.ID）；查询按会话强制过滤（铁律6）")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "可用分润余额(分)")
    @TableField("BALANCE_FEN")
    private Long balanceFen;

    @Schema(description = "提现审核冻结中(分)")
    @TableField("FROZEN_FEN")
    private Long frozenFen;

    @Schema(description = "冲减待补差额(分)（D-420）：>0 时禁止提现；后续分润入账先补此差额，补足即解除限制；恒>=0")
    @TableField("CLAWBACK_DEFICIT_FEN")
    private Long clawbackDeficitFen;

    @Schema(description = "乐观锁版本")
    @TableField("VERSION")
    private Integer version;
}
