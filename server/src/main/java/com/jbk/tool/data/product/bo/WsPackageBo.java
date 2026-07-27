package com.jbk.tool.data.product.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 水卡套餐业务对象（PC 后台：分页/新增/修改/上下架）
 *
 * <p>数值域（售价/水量/赠送/有效期/单价）不在注解层重复设上限：唯一规则入口是
 * {@code RechargeLimits.validatePackage}，避免出现第二份可漂移的校验口径。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsPackageBo", description = "水卡套餐业务对象")
public class WsPackageBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "套餐信息不为空")
    private Long id;

    @Schema(description = "套餐名称(max50)")
    @NotEmpty(groups = InsertGroup.class, message = "套餐名称不为空")
    @Size(groups = InsertGroup.class, max = 50, message = "套餐名称长度不能超过50")
    private String packageName;

    @Schema(description = "售价(分)")
    @NotNull(groups = InsertGroup.class, message = "套餐售价不为空")
    private Long payAmount;

    @Schema(description = "兑换水量(毫升)，0=纯余额充值套餐")
    @NotNull(groups = InsertGroup.class, message = "套餐水量不为空")
    private Long waterMl;

    @Schema(description = "赠送余额(分)，空按 0 处理")
    private Long bonusAmount;

    @Schema(description = "有效期(天)，空=永久")
    private Integer expireDays;

    @Schema(description = "可用范围JSON。由前端范围选择器组装（scopeType + 三维 ID 数组），"
            + "服务端以唯一 WaterCardScope 终审并落规范化指纹；空=未配置（首次购卡不可购，充值不加限制）")
    private String scopeJson;

    @Schema(description = "状态(1330)：1在售 2下架。仅新增时作为初始状态；修改不改状态，状态变更走 /shelf CAS")
    @NotNull(groups = InsertGroup.class, message = "套餐状态不为空")
    private Integer packageStatus;

    @Schema(description = "备注(max500)")
    @Size(max = 500, message = "备注长度不能超过500")
    private String packageRemark;

    @Schema(description = "上下架目标状态(1330)：1上架 2下架（仅 /shelf 使用）")
    private Integer targetStatus;
}
