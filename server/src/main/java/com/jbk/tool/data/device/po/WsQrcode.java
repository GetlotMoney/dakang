package com.jbk.tool.data.device.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 设备二维码表 Po（新版/旧版/万能码 → 设备/出水口映射）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_qrcode")
@Schema(name = "WsQrcode", description = "设备二维码表")
public class WsQrcode extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "码内容(max200)，扫码原文，业务唯一，代码层查重")
    @TableField("QRCODE_CONTENT")
    private String qrcodeContent;

    @Schema(description = "类型(1303)：1新版设备码 2旧版设备码 3万能码")
    @TableField("QRCODE_TYPE")
    private Integer qrcodeType;

    @Schema(description = "绑定设备ID（万能码可空，扫码后选设备）")
    @TableField("DEVICE_ID")
    private Long deviceId;

    @Schema(description = "绑定出水口ID（可空，空则扫码后选口）")
    @TableField("OUTLET_ID")
    private Long outletId;

    @Schema(description = "状态(10)：1正常 2禁用")
    @TableField("QRCODE_STATUS")
    private Integer qrcodeStatus;
}
