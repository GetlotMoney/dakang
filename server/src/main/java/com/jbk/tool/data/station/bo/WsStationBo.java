package com.jbk.tool.data.station.bo;

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
 * 水站业务对象
 * <p>渠道归属字段（CHANNEL_USER_ID）一期仅库表预留，不开放录入，故 Bo 不承载。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsStationBo", description = "水站业务对象")
public class WsStationBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "水站信息不为空")
    private Long id;

    @Schema(description = "水站名称(max50)")
    @NotEmpty(groups = InsertGroup.class, message = "水站名称不为空")
    @Size(groups = InsertGroup.class, max = 50, message = "水站名称长度不能超过50")
    private String stationName;

    @Schema(description = "水站编码(max50)")
    @NotEmpty(groups = InsertGroup.class, message = "水站编码不为空")
    @Size(groups = InsertGroup.class, max = 50, message = "水站编码长度不能超过50")
    private String stationCode;

    @Schema(description = "所属区域(max50)")
    @NotEmpty(groups = InsertGroup.class, message = "所属区域不为空")
    @Size(groups = InsertGroup.class, max = 50, message = "所属区域长度不能超过50")
    private String stationRegion;

    @Schema(description = "详细地址(max200)")
    @NotEmpty(groups = InsertGroup.class, message = "详细地址不为空")
    @Size(groups = InsertGroup.class, max = 200, message = "详细地址长度不能超过200")
    private String stationAddress;

    @Schema(description = "经度(max20)")
    @Size(max = 20, message = "经度长度不能超过20")
    private String stationLng;

    @Schema(description = "纬度(max20)")
    @Size(max = 20, message = "纬度长度不能超过20")
    private String stationLat;

    @Schema(description = "状态(10)：1正常 2禁用")
    @NotNull(groups = InsertGroup.class, message = "状态不为空")
    private Integer stationStatus;

    @Schema(description = "机主用户ID（可选，绑定后机主端可见该站数据）")
    private Long ownerUserId;

    @Schema(description = "备注(max500)")
    @Size(max = 500, message = "备注长度不能超过500")
    private String stationRemark;
}
