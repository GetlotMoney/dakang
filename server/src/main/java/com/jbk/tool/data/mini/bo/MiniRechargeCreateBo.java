package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

import java.io.Serializable;

/**
 * 创建充值订单入参（L2 契约 §4.2）。
 *
 * <p>ID 一律用十进制字符串承载，禁止 JS number（Long 精度）。
 * <b>不接受</b>自定义金额、前端金额、前端水量、userId 或前端指定的 PAY_SOURCE——
 * 金额与来源全部由服务端从套餐与受信任适配器取。</p>
 */
@Data
@Schema(name = "MiniRechargeCreateBo", description = "创建充值订单入参")
public class MiniRechargeCreateBo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * L2-A（决策 A2/A7）：cardId 缺省 = 首次购卡（purchase 态），服务端在支付成功后建卡；
     * 传值 = 已有卡充值（recharge 态）。同一 requestId 不允许在两种模式间切换。
     */
    @Schema(description = "目标水卡ID（正十进制字符串）；缺省表示首次购卡", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String cardId;

    @NotBlank(message = "packageId 不能为空")
    @Schema(description = "套餐ID（正十进制字符串）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String packageId;

    @NotBlank(message = "requestId 不能为空")
    @Schema(description = "幂等键：小写带连字符 UUID，同次网络重试复用", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;
}
