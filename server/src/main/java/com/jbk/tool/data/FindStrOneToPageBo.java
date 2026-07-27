package com.jbk.tool.data;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @ClassName FindStrOneToPageBo
 * @Author xs
 * @Date 2024/6/11 9:05
 * @Version 1.0
 */
@Data
public class FindStrOneToPageBo extends PageBo {
    @Schema(description = "请求")
    private String findStr;
}
