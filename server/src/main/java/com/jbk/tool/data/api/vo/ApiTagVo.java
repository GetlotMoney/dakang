package com.jbk.tool.data.api.vo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Getter
@Setter
@Accessors(chain = true)
@TableName("api_tag")
@Schema(name = "ApiTagVo", description = "$!{table.comment}")
public class ApiTagVo  extends BaseEntityVo implements Serializable {

    private static  final  long serialVersionUID = 1L;


    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "人员标签max20")
    @TableId("Tag_Name")
    private String tagName;
}

