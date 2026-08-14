package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallStockFlow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 商城库存流水 Mapper（E2E-09 S1）：只增表。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Mapper
public interface WsMallStockFlowMapper extends BaseMapper<WsMallStockFlow> {

    /**
     * 幂等预读：绕过 {@code @TableLogic} 原样读键——uk 不含 DATA_STATUS，
     * 被误标逻辑删除的流水仍占键、仍参与等价核验，绝不解锁重放。
     */
    @Select("SELECT * FROM ws_mall_stock_flow WHERE BIZ_IDEMPOTENCY_KEY = #{bizKey}")
    WsMallStockFlow selectByKeyIncludingDeleted(@Param("bizKey") String bizKey);
}
