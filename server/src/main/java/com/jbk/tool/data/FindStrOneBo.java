package com.jbk.tool.data;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @ClassName FindStrOneBo
 * @Author xs
 * @Date 2024/6/11 9:04
 * @Version 1.0
 */
@Data
public class FindStrOneBo {
    @Schema(description = "请求")
    private String findStr;
}

