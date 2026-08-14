package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallAfterSaleTrace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商城售后轨迹 Mapper（E2E-09 S4）。
 *
 * @author dakang
 * @since 2026-08-10
 */
@Mapper
public interface WsMallAfterSaleTraceMapper extends BaseMapper<WsMallAfterSaleTrace> {

    @Select("SELECT * FROM ws_mall_after_sale_trace WHERE BIZ_IDEMPOTENCY_KEY = #{key}")
    WsMallAfterSaleTrace selectByKeyIncludingDeleted(@Param("key") String key);

    @Select("""
            SELECT * FROM ws_mall_after_sale_trace
             WHERE DATA_STATUS = 0 AND AFTER_SALE_NO = #{no}
             ORDER BY TRACE_TIME ASC, ID ASC""")
    List<WsMallAfterSaleTrace> selectTimeline(@Param("no") String no);
}
