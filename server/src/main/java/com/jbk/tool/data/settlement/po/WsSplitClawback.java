package com.jbk.tool.data.settlement.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分润冲减事实 Po（D-420 R1 两段式）。
 *
 * <p>每（售后动作,分账行）一行：登记与客户退款成功同事务（outbox 语义），
 * 执行独立事务可重试可转人工；uk(ACTION_ID,SPLIT_ID) 是重放与并发登记的库层幂等闸。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
@TableName("ws_split_clawback")
public class WsSplitClawback implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "逻辑删除")
    @TableLogic
    @TableField("DATA_STATUS")
    private Integer dataStatus;

    @TableField("CREATE_BY")
    private Long createBy;

    @TableField("CREATE_TIME")
    private String createTime;

    @TableField("UPDATE_BY")
    private Long updateBy;

    @TableField("UPDATE_TIME")
    private String updateTime;

    @Schema(description = "售后动作ID")
    @TableField("ACTION_ID")
    private Long actionId;

    @Schema(description = "订单ID")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "分账行ID")
    @TableField("SPLIT_ID")
    private Long splitId;

    @Schema(description = "本次应冲金额(分)")
    @TableField("CLAWBACK_AMOUNT")
    private Long clawbackAmount;

    @Schema(description = "状态(1387)：1待处理 2已完成 3需人工")
    @TableField("CLAWBACK_STATUS")
    private Integer clawbackStatus;

    @Schema(description = "处理备注")
    @TableField("PROCESS_REMARK")
    private String processRemark;
}
