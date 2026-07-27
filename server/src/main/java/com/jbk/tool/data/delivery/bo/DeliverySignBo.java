package com.jbk.tool.data.delivery.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 配送三照签收入参（E2E-03 规则12/13：三照缺一不可、结构化数量、时间服务端生成）。
 *
 * <p>照片不收草稿时间——权威时间在签收动作发生时由服务端统一写入（Mock 契约第五轮审计口径）。</p>
 */
@Data
@Schema(name = "DeliverySignBo", description = "配送三照签收入参")
public class DeliverySignBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "taskNo 不能为空")
    @Schema(description = "任务号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskNo;

    @NotNull(message = "expectedVersion 不能为空")
    @Schema(description = "期望乐观锁版本（规则11）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer expectedVersion;

    @NotNull(message = "实际配送数量不能为空")
    @Min(value = 1, message = "实际配送数量必须大于0")
    @Max(value = 99, message = "实际配送数量超出上限")
    @Schema(description = "实际配送数量（结构化数字）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer actualDeliveryCount;

    @NotNull(message = "实际回收数量不能为空")
    @Min(value = 0, message = "实际回收数量不能为负")
    @Max(value = 99, message = "实际回收数量超出上限")
    @Schema(description = "实际回收数量（结构化数字）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer actualReturnCount;

    @Schema(description = "定位记录状态(1353)：1已记录 2未记录；缺省按未记录处理，禁止文案超出实际证据")
    private Integer locationStatus;

    @NotNull(message = "签收三照不能为空")
    @Valid
    @Schema(description = "三照：门牌/水品/摆放各一，缺一不可", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<SignPhotoBo> photos;

    /** 单张签收照片（受控媒体键引用；时间由服务端签收动作统一覆盖，不采信草稿） */
    @Data
    @Schema(name = "SignPhotoBo", description = "签收照片")
    public static class SignPhotoBo implements Serializable {

        private static final long serialVersionUID = 1L;

        @NotNull(message = "照片类型不能为空")
        @Schema(description = "照片类型：1门牌 2水品 3摆放", requiredMode = Schema.RequiredMode.REQUIRED)
        private Integer type;

        @NotBlank(message = "mediaKey 不能为空")
        @Schema(description = "受控媒体键（ws_delivery_media.MEDIA_KEY）", requiredMode = Schema.RequiredMode.REQUIRED)
        private String mediaKey;

        @Schema(description = "纬度（声明定位已记录时必填且须合法）")
        private Double latitude;

        @Schema(description = "经度（声明定位已记录时必填且须合法）")
        private Double longitude;
    }
}
