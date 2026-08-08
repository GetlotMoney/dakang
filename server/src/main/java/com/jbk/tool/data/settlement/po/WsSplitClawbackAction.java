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
 * 分润冲减动作级 outbox Po（D-420 R2）。
 *
 * <p>客户退款成功事务内<b>唯一</b>写入的冲减登记：单行 INSERT、ACTION_ID 唯一，
 * 冻结 orderId/actionType/refundProductFen 供执行段与权威动作逐字核验。
 * 分摊/校验/明细生成全部在独立执行事务完成——登记路径不可失败，
 * 冲减侧任何异常都不回滚客户退款。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
@TableName("ws_split_clawback_action")
public class WsSplitClawbackAction implements Serializable {

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

    @Schema(description = "售后动作ID（唯一）")
    @TableField("ACTION_ID")
    private Long actionId;

    @Schema(description = "订单ID（登记时冻结）")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "动作类型（登记时冻结）：1卡内退款 3机构退款")
    @TableField("ACTION_TYPE")
    private Integer actionType;

    @Schema(description = "水品实退金额(分)（登记时冻结，执行段分摊基数）")
    @TableField("REFUND_PRODUCT_FEN")
    private Long refundProductFen;

    @Schema(description = "状态(1387)：1待处理 2已完成 3需人工")
    @TableField("OUTBOX_STATUS")
    private Integer outboxStatus;

    @Schema(description = "处理备注")
    @TableField("PROCESS_REMARK")
    private String processRemark;
}
