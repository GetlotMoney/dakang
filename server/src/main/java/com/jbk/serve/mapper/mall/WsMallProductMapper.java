package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallProduct;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商城商品 SPU Mapper（E2E-09 S1）。上下架状态迁移必须走 {@link #casStatus}——
 * VERSION+前态双条件，影响行数即并发裁决。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Mapper
public interface WsMallProductMapper extends BaseMapper<WsMallProduct> {

    /** 上下架 CAS：状态+版本双前态；返回影响行数（0=状态已被并发改动）。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE ws_mall_product
               SET PRODUCT_STATUS = #{toStatus}, VERSION = VERSION + 1,
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND VERSION = #{version} AND PRODUCT_STATUS = #{fromStatus}
               AND DATA_STATUS = 0""")
    int casStatus(@org.apache.ibatis.annotations.Param("id") Long id,
                  @org.apache.ibatis.annotations.Param("version") Integer version,
                  @org.apache.ibatis.annotations.Param("fromStatus") Integer fromStatus,
                  @org.apache.ibatis.annotations.Param("toStatus") Integer toStatus,
                  @org.apache.ibatis.annotations.Param("operator") Long operator,
                  @org.apache.ibatis.annotations.Param("now") String now);
}
