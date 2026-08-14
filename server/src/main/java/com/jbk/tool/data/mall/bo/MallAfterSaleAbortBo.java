package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 换货补发中止入参（E2E-09 S4）。
 *
 * <p>原因必填：中止会释放已预占的库存并把售后单交回人工，是一个改变资金与库存去向的
 * 动作。没有原因的中止在事后看只是一条状态变更，谁也说不出当时发生了什么。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
@Data
@Accessors(chain = true)
public class MallAfterSaleAbortBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "售后单号")
    @NotBlank(message = "售后单号不能为空")
    @Size(max = 32, message = "售后单号过长")
    private String afterSaleNo;

    @Schema(description = "中止原因")
    @NotBlank(message = "请填写中止原因")
    @Size(max = 200, message = "中止原因不超过200字")
    private String abortReason;
}
