package com.jbk.tool.data.aftersale.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 待接单取消入参（E2E-04 包A）。
 *
 * <p><b>只有订单号，这是完整的入参而不是省略。</b>取消的返还额度恒等于整单满额，
 * 由服务端从订单冻结快照与原扣款流水推出；能否取消由订单状态、任务状态与归属决定。
 * 金额、水量、退款维度、目标卡任何一项出现在请求体里，都等于把「退多少、退到哪」
 * 交给调用方决定。用户身份同样不收（铁律6：userId 只从会话取）。</p>
 */
@Data
@Schema(name = "DeliveryCancelBo", description = "待接单取消配送订单入参")
public class DeliveryCancelBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "订单号不能为空")
    @Size(max = 32, message = "订单号过长")
    @Schema(description = "配送订单号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;
}
