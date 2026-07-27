package com.jbk.tool.data;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import jakarta.validation.constraints.NotEmpty;

/**
 *@ClassName LocationVo
 *@Author xs
 *@Date 2025/9/13 17:49
 *@Version 1.0
 */
@Data
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class LocationVo {
    @Schema(description = "经度")
    private String longitude;

    @Schema(description = "纬度")
    private String latitude;
}

