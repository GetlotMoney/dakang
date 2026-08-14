package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallWarehouseOperator;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 商城前置仓操作员归属 Mapper（E2E-09 S3）。
 *
 * @author dakang
 * @since 2026-08-09
 */
@Mapper
public interface WsMallWarehouseOperatorMapper extends BaseMapper<WsMallWarehouseOperator> {

    /** 该操作员是否归属该前置仓（且绑定未解除）。空结果即跨仓，默认拒绝。 */
    @Select("""
            SELECT COUNT(*) FROM ws_mall_warehouse_operator
             WHERE DATA_STATUS = 0 AND WAREHOUSE_ID = #{warehouseId}
               AND OPERATOR_ID = #{operatorId}""")
    int countActiveScope(@Param("warehouseId") Long warehouseId,
                         @Param("operatorId") Long operatorId);
}
