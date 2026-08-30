package com.jbk.tool.data.identity.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MiniPublicLeadBo {
    @NotNull private Long leadId;
}
