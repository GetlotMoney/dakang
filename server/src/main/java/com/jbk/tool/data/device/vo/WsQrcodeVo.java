package com.jbk.tool.data.device.vo;

import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 设备二维码响应对象（一期只读展示）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsQrcodeVo", description = "设备二维码响应对象")
public class WsQrcodeVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "码内容(max200)")
    private String qrcodeContent;

    @Schema(description = "类型(1303)：1新版设备码 2旧版设备码 3万能码")
    private Integer qrcodeType;

    @Schema(description = "绑定设备ID")
    private Long deviceId;

    @Schema(description = "绑定出水口ID")
    private Long outletId;

    @Schema(description = "绑定出水口编号（关联派生）")
    private Integer outletNo;

    @Schema(description = "状态(10)：1正常 2禁用")
    private Integer qrcodeStatus;
}
