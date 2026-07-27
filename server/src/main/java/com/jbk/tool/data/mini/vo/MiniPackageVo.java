package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 小程序充值套餐 Vo（L2-READ：真实套餐只读）。
 *
 * <p>只暴露展示与下单所需字段。{@code SCOPE_JSON} 属可用范围配置，
 * 由服务端在下单/取水时校验，不下发给前端（避免把范围规则变成可被前端解读/绕过的输入）。</p>
 *
 * <p>Long 型 ID 与金额经全局 Jackson 序列化为字符串防 JS 精度丢失。</p>
 */
@Data
@Schema(name = "MiniPackageVo", description = "小程序充值套餐")
public class MiniPackageVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "套餐ID（ws_package.ID）")
    private Long packageId;

    @Schema(description = "套餐名称")
    private String packageName;

    @Schema(description = "售价(分)")
    private Long payAmountFen;

    @Schema(description = "兑换水量(毫升)，0=纯余额充值套餐")
    private Long waterMl;

    @Schema(description = "赠送余额(分)")
    private Long bonusAmountFen;

    @Schema(description = "折算单价快照(分/升，字符串保留两位)")
    private String unitPriceSnap;

    @Schema(description = "有效期(天)，空=永久")
    private Integer expireDays;

    @Schema(description = "当前套餐范围配置是否完整合法，可用于发起购买")
    private Boolean purchasable;

    @Schema(description = "备注")
    private String packageRemark;
}
