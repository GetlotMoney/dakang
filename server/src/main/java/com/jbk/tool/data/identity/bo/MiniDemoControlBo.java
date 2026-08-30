package com.jbk.tool.data.identity.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MiniDemoControlBo {
    @NotBlank private String nextPayResult;
    @NotBlank private String nextDeviceResult;
    @NotNull private Integer deliveryAuto;
}
