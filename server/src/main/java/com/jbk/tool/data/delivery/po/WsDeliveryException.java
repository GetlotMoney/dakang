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
 * 配送异常记录表 Po（只插入不更新；上报限任务归属配送员，且任务处于 2/3/4 履约中）。
 *
 * @author dakang
 * @since 2026-07-23
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_delivery_exception")
@Schema(name = "WsDeliveryException", description = "配送异常记录表")
public class WsDeliveryException extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "配送任务ID")
    @TableField("TASK_ID")
    private Long taskId;

    @Schema(description = "上报配送员ID（必须等于任务归属配送员）")
    @TableField("COURIER_ID")
    private Long courierId;

    @Schema(description = "异常原因(1354)：1联系不上用户 2地址异常 3数量问题 4货物破损 5其他")
    @TableField("EXCEPTION_REASON")
    private Integer exceptionReason;

    @Schema(description = "异常说明(max500)")
    @TableField("EXCEPTION_DESC")
    private String exceptionDesc;

    @Schema(description = "举证JSON数组（受控媒体键）")
    @TableField("EVIDENCE_REFS")
    private String evidenceRefs;
}
