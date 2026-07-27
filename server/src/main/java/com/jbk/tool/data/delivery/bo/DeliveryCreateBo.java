package com.jbk.tool.data.delivery.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建配送订单入参（E2E-03 包A / 契约 /mini/delivery/order/create）。
 *
 * <p>ID 一律十进制字符串（规则20：Long 前端 string）；金额一概不收前端值，
 * 由服务端按容器规格价目计算并快照。userId 只从会话取（铁律6）。</p>
 */
@Data
@Schema(name = "DeliveryCreateBo", description = "创建配送订单入参")
public class DeliveryCreateBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "requestId 不能为空")
    @Schema(description = "幂等键：小写带连字符 UUID，同次网络重试复用", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;

    @NotBlank(message = "cardId 不能为空")
    @Schema(description = "支付水卡ID（正十进制字符串，须为本人卡）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String cardId;

    @NotBlank(message = "stationId 不能为空")
    @Schema(description = "配送水站ID（正十进制字符串）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String stationId;

    @NotBlank(message = "waterTypeId 不能为空")
    @Schema(description = "水种ID（正十进制字符串）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String waterTypeId;

    @NotBlank(message = "容器规格不能为空")
    @Schema(description = "容器规格：3L袋/5L桶/10L桶/20L桶", requiredMode = Schema.RequiredMode.REQUIRED)
    private String containerSpec;

    @NotNull(message = "配送数量不能为空")
    @Min(value = 1, message = "配送数量必须大于0")
    @Max(value = 99, message = "配送数量超出单笔上限")
    @Schema(description = "配送数量(桶/袋)", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer deliveryCount;

    @NotNull(message = "计划回收数量不能为空")
    @Min(value = 0, message = "计划回收数量不能为负")
    @Max(value = 99, message = "计划回收数量超出单笔上限")
    @Schema(description = "计划回收空桶数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer planReturnCount;

    @NotBlank(message = "收水地址不能为空")
    @Size(max = 200, message = "收水地址过长")
    @Schema(description = "收水地址(max200)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String receiveAddress;

    @NotBlank(message = "收货电话不能为空")
    @Schema(description = "收货电话（11位手机号，服务端校验）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String receivePhone;

    @NotNull(message = "配送方式不能为空")
    @Schema(description = "配送方式：1即时 2预约 3自动补货", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer deliveryMode;

    @Schema(description = "预约配送时间(yyyyMMddHHmmss)；方式2必填且须晚于当前时间")
    private String scheduledTime;

    @Schema(description = "自动补货固定周期天数(3~90)；方式3必填（REQ-011 显式配置）")
    private Integer autoRefillIntervalDays;

    /**
     * 支付方式（D-214 二选一，1346 子集）：2 全余额（水费+配送费均扣 BALANCE_AMOUNT）；
     * 3 混合结算（水费按容器水量扣 BALANCE_ML，配送费仍扣 BALANCE_AMOUNT——配送费是服务费，
     * 不得用毫升支付）。null 按 2 兼容：老验收包（E2E-03 封板前的调用方）无此字段。
     */
    @Schema(description = "支付方式(1346)：2水卡余额 3水卡水量+余额付配送费；缺省按2")
    private Integer payWay;
}
