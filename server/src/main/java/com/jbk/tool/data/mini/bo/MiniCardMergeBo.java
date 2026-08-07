package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

/**
 * 赠卡合并入正式水卡入参（D-415）。
 *
 * <p>只收赠卡 cardId：目标正式水卡由服务端在锁内自动定位（付费卡仅一张，D-417），
 * 不收 userId（铁律6，归属由会话登录人强制圈定）。</p>
 */
@Data
@Schema(name = "MiniCardMergeBo", description = "赠卡合并入正式水卡入参")
public class MiniCardMergeBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "cardId 不能为空")
    @Schema(description = "待合并的赠卡ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long cardId;
}
