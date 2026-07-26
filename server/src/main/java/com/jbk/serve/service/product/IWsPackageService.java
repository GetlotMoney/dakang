package com.jbk.serve.service.product;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.product.bo.WsPackageBo;
import com.jbk.tool.data.product.vo.WsPackageVo;

/**
 * 水卡套餐后台服务（PC 5+1 水种套餐模块）
 *
 * @author dakang
 * @since 2026-07-23
 */
public interface IWsPackageService {

    /**
     * 分页查询（名称模糊 + 状态精确）
     */
    PageDataVo<WsPackageVo> pageData(WsPackageBo packageBo);

    /**
     * 新增套餐。数值域走 RechargeLimits 唯一校验；范围非空时必须通过 WaterCardScope 规范化，
     * 单价快照由服务端按 售价÷水量 派生，不信任前端。
     */
    Long saveData(WsPackageBo packageBo);

    /**
     * 修改套餐（不改状态；上下架走 {@link #shelfData}）。
     * 历史订单不受影响：下单时已冻结 PACKAGE_SNAP 快照。
     */
    Boolean updateData(WsPackageBo packageBo);

    /**
     * 上下架：CAS 条件更新（前置状态精确匹配）并校验影响行数，避免并发覆盖。
     */
    Boolean shelfData(WsPackageBo packageBo);
}
