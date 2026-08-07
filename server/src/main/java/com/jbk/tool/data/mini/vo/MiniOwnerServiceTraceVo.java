package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 机主服务申请轨迹条目（取自领域事件，按工单号联查）。
 * 只回时间/端口/变化文本，不暴露操作者内部ID与原始报文结构。
 *
 * @author dakang
 * @since 2026-07-30
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniOwnerServiceTraceVo", description = "机主服务申请轨迹条目")
public class MiniOwnerServiceTraceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "发生时间")
    private String eventTime;

    @Schema(description = "操作端标签：平台/机主/系统")
    private String actorLabel;

    @Schema(description = "变化说明")
    private String detail;
}
