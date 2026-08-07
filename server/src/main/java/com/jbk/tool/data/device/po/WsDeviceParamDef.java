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
 * 设备参数定义注册表 Po（REQ-213）
 *
 * <p>单调收紧语义：本表可以是空的。未登记的键仍可下发（保持 E2E-05 已验收的参数同步能力），
 * 一旦某键在此登记且启用，平台立刻对它施加类型/值域/枚举校验。登记只会更严，永不更松。</p>
 *
 * @author dakang
 * @since 2026-08-04
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_device_param_def")
@Schema(name = "WsDeviceParamDef", description = "设备参数定义注册表")
public class WsDeviceParamDef extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 定义状态：启用才施加校验。 */
    public static final int STATUS_ENABLED = 1;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属指令类型(1320)：6参数同步 8价格同步")
    @TableField("CMD_TYPE")
    private Integer cmdType;

    @Schema(description = "参数键名")
    @TableField("PARAM_KEY")
    private String paramKey;

    @Schema(description = "中文名")
    @TableField("PARAM_NAME")
    private String paramName;

    @Schema(description = "值类型：1整数 2小数 3字符串 4布尔")
    @TableField("VALUE_TYPE")
    private Integer valueType;

    @Schema(description = "单位")
    @TableField("VALUE_UNIT")
    private String valueUnit;

    @Schema(description = "数值下限（含）")
    @TableField("VALUE_MIN")
    private String valueMin;

    @Schema(description = "数值上限（含）")
    @TableField("VALUE_MAX")
    private String valueMax;

    @Schema(description = "允许值枚举，逗号分隔")
    @TableField("VALUE_ENUM")
    private String valueEnum;

    @Schema(description = "状态：1启用 2停用")
    @TableField("DEF_STATUS")
    private Integer defStatus;

    @Schema(description = "备注：来源依据")
    @TableField("DEF_REMARK")
    private String defRemark;
}
