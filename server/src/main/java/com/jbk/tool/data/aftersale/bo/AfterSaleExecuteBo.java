package com.jbk.tool.data.aftersale.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * PC 售后动作执行入参（E2E-04 包A）。
 *
 * <p><b>本 Bo 只承载「执行哪一行」，不承载「执行成什么样」。</b>金额、水量、批准数量、
 * 目标状态一律不在此处出现：四元额度在登记时由服务端按订单快照算定并冻结，执行事务只读它
 * （{@code markSuccess} 还会把四列写进 CAS 的 WHERE 二次钉死）。给前端留任何一个额度入口，
 * 都等于把资金口径的真相源交给不可信输入。</p>
 *
 * <p><b>两个定位键都必填，且必须指向同一行。</b>{@code id} 是定位键，{@code afterSaleNo} 是共键复核：
 * PC 台账行同时带回这两列，执行时服务端逐一比对。只传 ID 时，一张停留在浏览器里的过期列表
 * （分页翻页、他人已处理后行序变化）足以让运营点中另一笔返还——两笔都是合法待执行动作，
 * 库层的唯一键与状态 CAS 都拦不住，因为那确实是一次「对某行的合法执行」。共键复核把这类
 * 误操作挡在认领之前，代价只是前端多回传一个已有字段。</p>
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
