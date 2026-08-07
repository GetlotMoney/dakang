package com.jbk.tool.data.aftersale.bo;

import com.jbk.tool.validator.group.IdGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import lombok.Data;

import java.io.Serializable;

/**
 * PC 取水异常核账入参（E2E-04 包A，售后来源 3取水异常核账）。
 *
 * <p><b>本 Bo 刻意没有任何金额、水量、目标状态或来源判别字段。</b>核账确认是「核对既有账本后
 * 推进订单终态」，判别（已退差待复核 / 未退差 / 账本断裂）与终态（零出水→7已退款、
 * 部分出水→4已完成）全部由 {@code IWaterAbnormalReconcileTxService} 在事务内按同一份规则重算，
 * 连预览接口给出的建议终态都不作数。多一个字段就多一条「前端说退多少就退多少」的旁路，
 * 而这条链上的订单本就处在账实待核的状态。</p>
 *
 * <p>{@code orderId} 同时登记 {@link IdGroup} 与 {@link Default}：预览只需订单ID
 * （走 IdGroup），确认还要求核账说明（走默认组），两个入口共用一个 Bo 而不各写一份。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Data
@Schema(name = "WaterAbnormalReconcileBo", description = "取水异常核账入参")
public class WaterAbnormalReconcileBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "订单ID不能为空", groups = { IdGroup.class, Default.class })
    @Schema(description = "订单ID（预览与确认共用）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long orderId;

    /**
     * 上限 200 与 {@code WaterAbnormalReconcileTxServiceImpl} 的 REMARK_MAX 同值：
     * 服务端仍会截断（那是防御），此处提前拒绝是为了让运营在提交时就看到长度问题，
     * 而不是事后发现台账里的说明被截了一半。
     */
    @NotBlank(message = "核账说明不能为空")
    @Size(max = 200, message = "核账说明过长")
    @Schema(description = "运营核账说明（落审计与台账计算快照，max200）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String handleRemark;
}
