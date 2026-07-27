package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

/**
 * 小程序水卡详情入参（L2-READ）。
 *
 * <p>铁律6：只收 cardId，**不收 userId**——归属一律由会话登录人在服务端强制圈定。</p>
 */
@Data
@Schema(name = "MiniCardDetailBo", description = "小程序水卡详情入参")
public class MiniCardDetailBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "cardId 不能为空")
    @Schema(description = "水卡ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long cardId;
}
