package com.jbk.tool.data.aftersale.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * Refund-Sim 模拟退款事实入参（E2E-04 包B，仅隔离环境注册）。
 *
 * <p><b>入参里刻意没有金额、币种与退款来源</b>：这三项由服务端从本地退款单与适配器取。
 * 若允许调用方传金额，模拟器就成了「任意金额退款」的入口——而 Refund-Sim 存在的意义
 * 是模拟服务方<b>对我们已发出的那笔请求</b>的答复，不是让调用方决定退多少。
 * 同理 REFUND_SOURCE 只能来自适配器常量（R0-8）。</p>
 *
 * <p>{@code result} 允许模拟失败，这是覆盖「错误事实处理」场景所必需的。</p>
 */
@Data
@Schema(name = "RefundSimNotifyBo", description = "Refund-Sim 模拟退款事实")
public class RefundSimNotifyBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "退款单号不能为空")
    @Schema(description = "商户退款单号(out_refund_no)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String refundNo;

    @Schema(description = "模拟结果：SUCCESS 退款成功 / CLOSED 退款关闭 / ABNORMAL 服务方异常；缺省 SUCCESS")
    private String result;

    @Schema(description = "事实序号：同一退款单模拟多条不同事实时用于区分幂等键；缺省 1。"
            + "刻意由调用方给出而非随机——随机会让「同一事实重复三次」无法被测出")
    private Integer factSeq;

    @Schema(description = "覆盖退款金额(分)，仅用于构造「错金额事实」的异常场景；缺省取本地退款单金额")
    private Long overrideAmountFen;

    @Schema(description = "覆盖商户订单号，仅用于构造「错订单事实」的异常场景；缺省取本地退款单")
    private String overrideOrderNo;
}
