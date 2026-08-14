package com.jbk.tool.data.aftersale.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * PC 售后动作执行入参（E2E-04 包A）。只承载「执行哪一行」：金额/水量/目标状态一律不收，
 * 四元额度由服务端按订单快照冻结，执行事务只读。
 * {@code id} 与 {@code afterSaleNo} 都必填且必须指向同一行（共键复核）：只传 ID 时过期列表足以让运营点中另一笔合法返还。
 *
 * @author dakang
 * @since 2026-07-29
 */
@Data
@Schema(name = "AfterSaleExecuteBo", description = "售后动作执行入参")
public class AfterSaleExecuteBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "售后动作ID不能为空")
    @Schema(description = "售后动作ID（台账行主键）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @NotBlank(message = "售后号不能为空")
    @Size(max = 32, message = "售后号过长")
    @Schema(description = "售后号（与 id 共键复核，两者必须指向同一行）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String afterSaleNo;
}
