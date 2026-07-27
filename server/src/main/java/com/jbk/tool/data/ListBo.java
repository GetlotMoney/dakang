package com.jbk.tool.data;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @ClassName ListBo
 * @Author xs
 * @Date 2024/6/11 9:46
 * @Version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ListBo<T> {
    // List集合
    @Schema(description = "list")
    private List<T> list;
}

