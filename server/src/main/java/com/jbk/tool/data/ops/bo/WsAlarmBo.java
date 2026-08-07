package com.jbk.tool.data.ops.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 设备告警业务对象（一期仅设备详情页按设备查询）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsAlarmBo", description = "设备告警业务对象")
public class WsAlarmBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键（处置动作用）")
    @NotNull(groups = IdGroup.class, message = "告警信息不为空")
    private Long id;

    @Schema(description = "设备ID（设备详情页按设备查询时必填，服务端校验；告警中心全量分页可空）")
    private Long deviceId;

    @Schema(description = "【筛选】告警状态(1361)")
    private Integer alarmStatus;

    @Schema(description = "【筛选】告警类型(1360)")
    private Integer alarmType;

    @Schema(description = "【筛选】告警等级(1304)")
    private Integer alarmLevel;
}
