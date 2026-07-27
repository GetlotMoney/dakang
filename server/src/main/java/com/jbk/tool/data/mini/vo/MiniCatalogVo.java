package com.jbk.tool.data.mini.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 小程序目录读模型 Vo（E2E-03 包B：U07/U08/D02 消费的水种与水站列表）。
 * <p>字段对齐 miniapp catalog.ts WaterType / StationSummary，保证小程序零改。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
public interface MiniCatalogVo {

    /** 水种列表项（ws_water_type；8 种水最终定义待甲方确认，placeholder=true 表示占位名）。 */
    @Data
    @Accessors(chain = true)
    @Schema(name = "MiniWaterTypeVo", description = "小程序水种")
    class MiniWaterTypeVo implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "水种ID")
        private Long id;

        @Schema(description = "水种名称")
        private String name;

        @Schema(description = "是否启用（WATER_STATUS=1）")
        private Boolean enabled;

        @Schema(description = "是否占位水种（8 种水最终定义待甲方确认，一期恒 true）")
        private Boolean placeholder;
    }

    /** 水站列表项（ws_station + 设备/出水口计数派生）。 */
    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "MiniStationVo", description = "小程序水站摘要")
    class MiniStationVo implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "水站ID")
        private Long id;

        @Schema(description = "水站名称")
        private String stationName;

        @Schema(description = "详细地址（区域+地址拼接展示）")
        private String address;

        @Schema(description = "距离（米）；未接定位能力恒为空，前端不得显示伪距离")
        private Long distanceMeters;

        @Schema(description = "启用出水口数量（关联 ws_device_outlet 派生）")
        private Integer availableOutletCount;

        @Schema(description = "在线设备数量（关联 ws_device 派生）")
        private Integer onlineDeviceCount;

        @Schema(description = "状态：OPEN=营业 CLOSED=停用（对齐 catalog.ts StationSummary.status）")
        private String status;
    }
}
