package com.jbk.tool.data;

import com.jbk.tool.validator.group.PageGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
public class PageBo {
    @NotNull(message = "current不能为空", groups = {PageGroup.class})
    @Schema(description = "【仅分页查询需要传递】当前页", required = true)
    private Long current;
    @NotNull(message = "size不能为空", groups = {PageGroup.class})
    @Schema(description = "【仅分页查询需要传递】大小", required = true)
    private Long size;
}
