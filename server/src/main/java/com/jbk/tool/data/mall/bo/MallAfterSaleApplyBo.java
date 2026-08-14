package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 商城售后申请入参（E2E-09 S4）。
 *
 * <p><b>刻意没有金额字段。</b>应退金额由服务端按原订单不可变明细算出——收下前端传来的
 * 金额等于把「退多少」的决定权交给调用方，而调用方是可以被伪造的。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Data
@Accessors(chain = true)
public class MallAfterSaleApplyBo {

    @Schema(description = "客户端请求号(UUID)：与会话用户组成申请幂等锚")
    @NotBlank(message = "请求号不能为空")
    @Size(max = 36, message = "请求号过长")
    private String requestId;

    @Schema(description = "原商城订单号")
    @NotBlank(message = "订单号不能为空")
    @Size(max = 32, message = "订单号过长")
    private String orderNo;

    @Schema(description = "售后类型(1400)：1退货退款 2同SKU换货 3未拣货整单取消退款")
    @NotNull(message = "请选择售后类型")
    private Integer afterSaleType;

    @Schema(description = "申请原因")
    @NotBlank(message = "请填写申请原因")
    @Size(max = 200, message = "申请原因不超过200字")
    private String applyReason;

    @Schema(description = "申请明细：整单取消时可为空，由服务端按订单全量展开")
    @Valid
    private List<Line> lines;

    /** 一行 = 一条原订单明细上的申请数量。 */
    @Data
    @Accessors(chain = true)
    public static class Line {

        @Schema(description = "原订单明细ID")
        @NotNull(message = "缺少订单明细")
        private Long orderItemId;

        @Schema(description = "申请数量(件)")
        @NotNull(message = "缺少申请数量")
        @Min(value = 1, message = "申请数量至少为1")
        private Integer quantity;
    }
}
