package com.jbk.tool.data.api.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * <p>
 * 字典类型表
 * </p>
 *
 * @author xs
 * @since 2025-09-04
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("api_dict_type")
@Schema(name = "ApiDictType", description = "$!{table.comment}")
public class ApiDictType implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键，自增ID")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "字典名称(max10)")
    @TableField("DICT_NAME")
    private String dictName;

    @Schema(description = "字典类型(max5)")
    @TableField("DICT_TYPE")
    private String dictType;

    @Schema(description = "字典备注(max150)")
    @TableField("DICT_REMARK")
    private String dictRemark;

    @Schema(description = "字典数据")
    @TableField(exist = false)
    private List<ApiDictData> dictDataList;
}


