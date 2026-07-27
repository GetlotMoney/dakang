package com.jbk.tool.data;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @ClassName FileInfoVo
 * @Author xs
 * @Date 2023/11/27 16:05
 * @Version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileInfo {
    @Schema(description = "文件地址")
    private String url;
    @Schema(description = "文件大小")
    private String size;
    @Schema(description = "文件名称")
    private String name;
}

