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
 * 设备上行消息表 Po（安全铁律 3：MSG_ID 唯一索引去重，断网补传不得重复计量）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_device_msg")
@Schema(name = "WsDeviceMsg", description = "设备上行消息表")
public class WsDeviceMsg extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "设备侧消息ID(max64)，断网补传去重键")
    @TableField("MSG_ID")
    private String msgId;

    @Schema(description = "设备ID")
    @TableField("DEVICE_ID")
    private Long deviceId;

    @Schema(description = "消息类型(1310)：1状态上报 2指令回执 3水量回传 4故障事件 5补传消息")
    @TableField("MSG_TYPE")
    private Integer msgType;

    @Schema(description = "来源MQTT主题(max100)")
    @TableField("MSG_TOPIC")
    private String msgTopic;

    @Schema(description = "消息原文JSON")
    @TableField("MSG_PAYLOAD")
    private String msgPayload;

    @Schema(description = "设备侧发生时间（补传时早于落库时间）")
    @TableField("DEVICE_TIME")
    private String deviceTime;

    @Schema(description = "处理状态(1311)：1待处理 2已处理 3处理失败 4重复丢弃")
    @TableField("HANDLE_STATUS")
    private Integer handleStatus;

    @Schema(description = "处理备注(max500)")
    @TableField("HANDLE_REMARK")
    private String handleRemark;
}
