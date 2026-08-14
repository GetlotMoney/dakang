package com.jbk.tool.data.aftersale.bo;

import com.jbk.tool.data.PageBo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * PC 售后台账查询入参（E2E-04 包A）。筛选维度与 {@code WsAfterSaleActionMapper.xml} 的 {@code <if>} 一一对应，
 * 新增字段必须同步改 XML 否则静默无效。只承载查询条件：金额/水量/目标状态由服务端从订单快照派生，绝不接受前端传入。
 *
 * @author dakang
 * @since 2026-07-29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "AdminAfterSaleActionBo", description = "售后台账查询入参")
public class AdminAfterSaleActionBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "售后动作ID（详情查询用）")
    private Long id;

    @Schema(description = "执行状态(1372)：1待执行 2执行中 3已完成 4可重试 5需人工对账 6已终止")
    private Integer actionStatus;

    @Schema(description = "售后来源(1370)：1配送取消 2配送申诉 3取水异常核账")
    private Integer sourceType;

    @Schema(description = "动作类型(1371)：1卡内退款 2卡内补偿 3机构退款 4补送")
    private Integer actionType;

    @Schema(description = "关键字：售后号/订单号/用户姓名/手机号模糊匹配")
    private String keyword;
}
