package com.jbk.tool.data.delivery.po;

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
 * 配送申诉表 Po（E2E-03：本人+24h 窗口创建；裁决只产生 3驳回/5补送待执行/2成立待补偿）。
 * <p>ACTIVE_TASK_KEY 是数据库生成列（状态1时=TASK_ID，否则 NULL）+ 唯一键，
 * 保证一任务至多一条活动申诉；Java 侧只读不写。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_delivery_appeal")
@Schema(name = "WsDeliveryAppeal", description = "配送申诉表")
public class WsDeliveryAppeal extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "配送任务ID（共键）")
    @TableField("TASK_ID")
    private Long taskId;

    @Schema(description = "配送订单ID（共键：必须等于任务的 ORDER_ID）")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "申诉用户ID（限订单本人，规则15）")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "申诉原因码(max20)：QUANTITY/QUALITY/DAMAGE/PLACEMENT/OTHER")
    @TableField("APPEAL_REASON")
    private String appealReason;

    @Schema(description = "申诉说明(max500)")
    @TableField("APPEAL_DESC")
    private String appealDesc;

    @Schema(description = "用户实收数量（结构化数字）")
    @TableField("RECEIVED_COUNT")
    private Integer receivedCount;

    @Schema(description = "申诉举证JSON数组（受控媒体键）")
    @TableField("APPEAL_PHOTOS")
    private String appealPhotos;

    @Schema(description = "配送员举证JSON数组：{description, evidenceRefs, time}（规则16）")
    @TableField("COURIER_EVIDENCES")
    private String courierEvidences;

    @Schema(description = "申诉状态(1352)：1待处理 2成立待补偿 3不成立驳回 4撤销 5补送待执行")
    @TableField("APPEAL_STATUS")
    private Integer appealStatus;

    @Schema(description = "处理人（api_employee.ID）")
    @TableField("HANDLE_BY")
    private Long handleBy;

    @Schema(description = "处理时间")
    @TableField("HANDLE_TIME")
    private String handleTime;

    @Schema(description = "处理结果(max500)")
    @TableField("HANDLE_RESULT")
    private String handleResult;

    @Schema(description = "活动申诉唯一占位（数据库生成列，只读）")
    @TableField(value = "ACTIVE_TASK_KEY", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private Long activeTaskKey;
}
