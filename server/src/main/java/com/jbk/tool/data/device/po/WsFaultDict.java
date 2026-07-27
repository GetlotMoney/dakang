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
 * 故障码字典表 Po（硬件协议原值 → 名称/等级/是否阻断下单）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_fault_dict")
@Schema(name = "WsFaultDict", description = "故障码字典表")
public class WsFaultDict extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "故障码(max20)，硬件协议原值")
    @TableField("FAULT_CODE")
    private String faultCode;

    @Schema(description = "故障名称(max50)")
    @TableField("FAULT_NAME")
    private String faultName;

    @Schema(description = "故障等级(1304)：1提示 2一般 3严重")
    @TableField("FAULT_LEVEL")
    private Integer faultLevel;

    @Schema(description = "是否阻断下单(1)：1否 2是")
    @TableField("BLOCK_ORDER_FLAG")
    private Integer blockOrderFlag;

    @Schema(description = "处理建议(max500)")
    @TableField("FAULT_ADVICE")
    private String faultAdvice;
}
