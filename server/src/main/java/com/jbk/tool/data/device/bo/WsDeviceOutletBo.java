package com.jbk.tool.data.device.bo;

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
 * 设备出水口业务对象
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsDeviceOutletBo", description = "设备出水口业务对象")
public class WsDeviceOutletBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "出水口信息不为空")
    private Long id;

    @Schema(description = "所属设备ID")
    @NotNull(groups = InsertGroup.class, message = "所属设备不为空")
    private Long deviceId;

    @Schema(description = "出水口编号（设备内序号 1/2/3...）")
    @NotNull(groups = InsertGroup.class, message = "出水口编号不为空")
    private Integer outletNo;

    @Schema(description = "水种ID（ws_water_type.ID）")
    @NotNull(groups = InsertGroup.class, message = "水种不为空")
    private Long waterTypeId;

    @Schema(description = "单价(分/升)")
    @NotEmpty(groups = InsertGroup.class, message = "单价不为空")
    @Size(groups = InsertGroup.class, max = 11, message = "单价长度不能超过11")
    private String outletPrice;

    @Schema(description = "状态(10)：1正常 2禁用")
    @NotNull(groups = InsertGroup.class, message = "状态不为空")
    private Integer outletStatus;
}
