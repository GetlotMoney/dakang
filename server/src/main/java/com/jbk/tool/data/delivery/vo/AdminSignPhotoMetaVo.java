package com.jbk.tool.data.delivery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 签收三照元数据 Vo（type/时间/GPS 来自签收事务落库的 SIGN_PHOTOS JSON，
 * 媒体核验字段继承 {@link AdminMediaRefVo}）。PC 只展示元数据，不渲染图片字节。
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@Schema(name = "AdminSignPhotoMetaVo", description = "签收三照元数据")
public class AdminSignPhotoMetaVo extends AdminMediaRefVo {

    private static final long serialVersionUID = 1L;

    @Schema(description = "照片类型：1门牌 2水品 3摆放")
    private Integer type;

    @Schema(description = "类型名称：门牌照/水品照/摆放照")
    private String typeLabel;

    @Schema(description = "拍摄记录时间（签收事务统一写入，与签收时间同源）")
    private String time;

    @Schema(description = "纬度（定位未记录为空）")
    private Double latitude;

    @Schema(description = "经度（定位未记录为空）")
    private Double longitude;
}
