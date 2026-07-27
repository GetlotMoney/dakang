package com.jbk.tool.data.product.vo;

import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 水卡套餐响应对象（PC 后台）
 *
 * <p>范围不下发 SCOPE_JSON 原文，只下发经唯一 WaterCardScope 规范化后的
 * scopeType + 三个 ID 集合（ID 为十进制字符串），供编辑弹窗回填选择器；
 * 非法/未配置时三者为空并以 scopeValid=false + 摘要说明兜底。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsPackageVo", description = "水卡套餐响应对象")
public class WsPackageVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "套餐名称(max50)")
    private String packageName;

    @Schema(description = "售价(分)")
    private Long payAmount;

    @Schema(description = "兑换水量(毫升)，0=纯余额充值套餐")
    private Long waterMl;

    @Schema(description = "赠送余额(分)")
    private Long bonusAmount;

    @Schema(description = "折算单价快照(分/升，字符串保留两位)，服务端按 售价÷水量 派生")
    private String unitPriceSnap;

    @Schema(description = "有效期(天)，空=永久")
    private Integer expireDays;

    @Schema(description = "状态(1330)：1在售 2下架")
    private Integer packageStatus;

    @Schema(description = "备注(max500)")
    private String packageRemark;

    @Schema(description = "范围类型：all/specified；未配置或非法时为空")
    private String scopeType;

    @Schema(description = "范围水站 ID（规范化十进制字符串，升序）")
    private List<String> stationIds;

    @Schema(description = "范围设备 ID（规范化十进制字符串，升序）")
    private List<String> deviceIds;

    @Schema(description = "范围出水口 ID（规范化十进制字符串，升序）")
    private List<String> outletIds;

    @Schema(description = "范围是否合法非空（决定首次购卡可选性，L2-A6）")
    private Boolean scopeValid;

    @Schema(description = "范围摘要（派生展示文本，不参与授权判定）")
    private String scopeSummary;
}
