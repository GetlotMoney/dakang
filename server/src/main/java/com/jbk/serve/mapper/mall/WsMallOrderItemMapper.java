package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallOrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商城订单明细 Mapper（E2E-09 S2）：只在创单时写入，之后只读。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Mapper
public interface WsMallOrderItemMapper extends BaseMapper<WsMallOrderItem> {

    /**
     * 按订单读明细，SKU 升序——预占/释放/实销遍历必须用这个顺序，
     * 与 (WAREHOUSE_ID, SKU_ID) 升序锁序一致（一单一仓，仓维度恒定）。
     */
    @Select("""
            SELECT * FROM ws_mall_order_item
             WHERE ORDER_ID = #{orderId} AND DATA_STATUS = 0
             ORDER BY SKU_ID ASC""")
    List<WsMallOrderItem> selectByOrderIdOrderBySku(@Param("orderId") Long orderId);
}
