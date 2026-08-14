package com.jbk.tool.data.api.vo;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@Setter
@Accessors(chain = true)
@TableName("api_dept")
@Schema(name = "ApiDeptTreeVo", description = "$!{table.comment}")
public class ApiDeptTreeVo extends ApiDeptVo{

    @Schema(description = "子部门")
    private List<ApiDeptTreeVo> children;
}


