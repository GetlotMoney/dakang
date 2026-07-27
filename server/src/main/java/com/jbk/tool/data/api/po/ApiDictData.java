package com.jbk.tool.data.api.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * <p>
 * 字典数据表
 * </p>
 *
 * @author xs
 * @since 2025-09-04
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("api_dict_data")
@Schema(name = "ApiDictData", description = "$!{table.comment}")
public class ApiDictData implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键，自增ID")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "样式")
    @TableField("DICT_CLASS")
    private String dictClass;

    @Schema(description = "是否默认(1)")
    @TableField("DICT_DEFAULT_FLAG")
    private Integer dictDefaultFlag;

    @Schema(description = "字典类型(max5)")
    @TableField("DICT_TYPE")
    private String dictType;

    @Schema(description = "字典排序")
    @TableField("DICT_SORT")
    private Integer dictSort;

    @Schema(description = "字典键值")
    @TableField("DICT_VALUE")
    private Integer dictValue;

    @Schema(description = "字典标签(max10)")
    @TableField("DICT_LABEL")
    private String dictLabel;
}


