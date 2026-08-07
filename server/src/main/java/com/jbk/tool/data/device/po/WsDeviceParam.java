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
 * 设备参数快照表 Po（REQ-213）
 *
 * <p>回答「这台设备当前参数是什么」。只在 result 成功（非 partial、非失败）后回写：
 * ACK 只代表受理，不代表设备真的改了参数，据 ACK 回写会让平台显示「已同步」而设备没改。</p>
 *
 * @author dakang
 * @since 2026-08-04
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_device_param")
@Schema(name = "WsDeviceParam", description = "设备参数快照表")
public class WsDeviceParam extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "设备ID")
    @TableField("DEVICE_ID")
    private Long deviceId;

    @Schema(description = "参数键名")
    @TableField("PARAM_KEY")
    private String paramKey;

    @Schema(description = "平台侧认为设备当前生效的值")
    @TableField("PARAM_VALUE")
    private String paramValue;

    @Schema(description = "该键的同步版本，每次成功回写 +1")
    @TableField("PARAM_VERSION")
    private Integer paramVersion;

    @Schema(description = "写入本值的指令号（溯源用）")
    @TableField("SOURCE_CMD_NO")
    private String sourceCmdNo;

    @Schema(description = "写入本值的指令ID；单调守卫，只有更大的 ID 才能覆盖")
    @TableField("SOURCE_CMD_ID")
    private Long sourceCmdId;

    @Schema(description = "设备回报成功的时间")
    @TableField("SYNC_TIME")
    private String syncTime;

    @Schema(description = "写入时该键是否已登记：0未登记 1已登记")
    @TableField("REGISTERED_FLAG")
    private Integer registeredFlag;
}
