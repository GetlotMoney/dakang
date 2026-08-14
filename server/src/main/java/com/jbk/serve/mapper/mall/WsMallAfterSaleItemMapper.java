package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallAfterSaleItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商城售后明细 Mapper（E2E-09 S4）。
 *
 * @author dakang
 * @since 2026-08-10
 */
@Mapper
public interface WsMallAfterSaleItemMapper extends BaseMapper<WsMallAfterSaleItem> {

    /** 按 SKU 升序：多行库存动作锁序固定，避免两笔售后交叉加锁死锁。 */
    @Select("""
            SELECT * FROM ws_mall_after_sale_item
             WHERE DATA_STATUS = 0 AND AFTER_SALE_ID = #{afterSaleId}
             ORDER BY SKU_ID ASC""")
    List<WsMallAfterSaleItem> selectByAfterSaleOrderBySku(@Param("afterSaleId") Long afterSaleId);
}
