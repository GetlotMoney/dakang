package com.jbk.serve.service.mall;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallQueryBo;
import com.jbk.tool.data.mall.bo.MallStockAdjustBo;
import com.jbk.tool.data.mall.vo.MallSkuCandidateVo;
import com.jbk.tool.data.mall.vo.MallStockAdjustResultVo;
import com.jbk.tool.data.mall.vo.MallStockFlowVo;
import com.jbk.tool.data.mall.vo.MallStockVo;

/**
 * 商城库存服务（E2E-09 S1）。
 *
 * <p>本期只开放人工入库/人工出库/盘点调整。数量变化恒走原子条件 UPDATE
 * （负库存库层不可达）；流水与库存同一事务；幂等锚=流水唯一键
 * uk_mall_stock_flow_biz_key（MALLADJ:&lt;requestId&gt;），同键重放逐字核验
 * 一致返回原结果、参数漂移拒绝。</p>
 */
public interface IMallStockService {

    PageDataVo<MallStockVo> page(MallQueryBo bo);

    /**
     * 库存动作 SKU 候选（R2-P0）：数据源 ws_mall_sku ⋈ ws_mall_product，独立于库存行
     * ——新 SKU 无库存行也可选中做首次入库；关键字远程搜索，单页硬上限 50。
     */
    PageDataVo<MallSkuCandidateVo> skuCandidates(MallQueryBo bo);

    /**
     * 人工库存动作：返回以幂等流水行为准的动作结果——首次与重放同源同值，
     * 重放拿到的是原动作的冻结后置值，不是重放时刻的当前库存（R1-P1-2）。
     */
    MallStockAdjustResultVo adjust(MallStockAdjustBo bo);

    PageDataVo<MallStockFlowVo> flowPage(MallQueryBo bo);
}
