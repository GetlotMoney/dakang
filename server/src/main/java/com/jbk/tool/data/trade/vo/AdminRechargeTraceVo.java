package com.jbk.tool.data.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端充值追溯证据。
 *
 * <p>{@code linkStatus=ok} 表示订单、支付事实、目标卡与唯一入账流水已经通过
 * 小程序订单详情同一套服务端校验；不一致时只返回原因，不返回可被误读为成功的详情。</p>
 */
@Data
@Schema(name = "AdminRechargeTraceVo", description = "管理端充值追溯证据")
public class AdminRechargeTraceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "ok=关联证据一致；mismatch=证据缺失或共键不一致")
    private String linkStatus;

    @Schema(description = "关联异常原因；linkStatus=mismatch 时有值")
    private String linkReason;

    @Schema(description = "已通过服务端校验的结构化充值详情；mismatch 时为空")
    private MiniRechargeDetailVo detail;
}
