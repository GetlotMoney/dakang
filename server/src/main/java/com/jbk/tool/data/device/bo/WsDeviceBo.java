package com.jbk.tool.data.device.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 设备业务对象
 * <p>在线状态/运行状态/心跳/故障码由设备上行链路维护，后台档案编辑不承载；
 * 渠道归属一期仅库表预留，Bo 不承载。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsDeviceBo", description = "设备业务对象")
public class WsDeviceBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "设备信息不为空")
    private Long id;

    @Schema(description = "设备唯一编号(max50)")
    @NotEmpty(groups = InsertGroup.class, message = "设备编号不为空")
    @Size(groups = InsertGroup.class, max = 50, message = "设备编号长度不能超过50")
    private String deviceNo;

    @Schema(description = "设备名称(max50)")
    @NotEmpty(groups = InsertGroup.class, message = "设备名称不为空")
    @Size(groups = InsertGroup.class, max = 50, message = "设备名称长度不能超过50")
    private String deviceName;

    @Schema(description = "设备型号(max50)")
    @NotEmpty(groups = InsertGroup.class, message = "设备型号不为空")
    @Size(groups = InsertGroup.class, max = 50, message = "设备型号长度不能超过50")
    private String deviceModel;

    @Schema(description = "所属水站ID")
    @NotNull(groups = InsertGroup.class, message = "所属水站不为空")
    private Long stationId;

    @Schema(description = "机主用户ID（可选）")
    private Long ownerUserId;

    @Schema(description = "固件版本(max50)")
    @Size(max = 50, message = "固件版本长度不能超过50")
    private String firmwareVersion;

    @Schema(description = "SIM卡ICCID(max50)")
    @Size(max = 50, message = "SIM卡ICCID长度不能超过50")
    private String simIccid;

    @Schema(description = "SIM运营商(max20)")
    @Size(max = 20, message = "SIM运营商长度不能超过20")
    private String simCarrier;

    @Schema(description = "备注(max500)")
    @Size(max = 500, message = "备注长度不能超过500")
    private String deviceRemark;

    // ===== 以下为查询筛选字段 =====

    @Schema(description = "【筛选】在线状态(1300)")
    private Integer onlineStatus;

    @Schema(description = "【筛选】运行状态(1301)")
    private Integer runStatus;
}
