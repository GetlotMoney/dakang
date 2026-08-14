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
 * 商城物流出站动作 Po（E2E-09 L1）。外呼不进数据库事务：业务事务只写本表，Worker 负责调适配器。
 * {@code REQUEST_SNAP} 是登记时冻结的入参，Worker 只按快照调用、不回读当前业务态（防慢重试口径分叉）。
 *
 * @author dakang
 * @since 2026-08-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_logistics_outbox")
public class WsMallLogisticsOutbox extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "目标包裹ID")
    @TableField("SHIPMENT_ID")
    private Long shipmentId;

    @Schema(description = "动作类型(1409)：1创建运单 2取消运单")
    @TableField("ACTION_TYPE")
    private Integer actionType;

    @Schema(description = "动作幂等键：MLOG:<shipmentId>:<actionType>")
    @TableField("BIZ_ACTION_KEY")
    private String bizActionKey;

    @Schema(description = "请求快照：登记时冻结的入参")
    @TableField("REQUEST_SNAP")
    private String requestSnap;

    @Schema(description = "处理状态(1408)")
    @TableField("PROCESSING_STATUS")
    private Integer processingStatus;

    @Schema(description = "重试次数：到上限转人工")
    @TableField("RETRY_COUNT")
    private Integer retryCount;

    @Schema(description = "下次重试时间：退避后才再取")
    @TableField("NEXT_RETRY_TIME")
    private String nextRetryTime;

    @Schema(description = "认领时间")
    @TableField("CLAIM_TIME")
    private String claimTime;

    @Schema(description = "租约到期")
    @TableField("LEASE_UNTIL")
    private String leaseUntil;

    @Schema(description = "最近一次失败原因")
    @TableField("LAST_ERROR")
    private String lastError;
}
