package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商城 SKU Mapper（E2E-09 S1）。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Mapper
public interface WsMallSkuMapper extends BaseMapper<WsMallSku> {

    /** 启用 SKU 的最低售价按商品聚合（小程序卡片「最低售价」唯一来源）。 */
    @Select("""
            SELECT PRODUCT_ID AS productId, MIN(SALE_PRICE) AS minSalePrice
              FROM ws_mall_sku
             WHERE SKU_STATUS = 1 AND DATA_STATUS = 0
             GROUP BY PRODUCT_ID""")
    List<MinPriceRow> minSalePriceByProduct();

    /** 聚合行：商品→启用 SKU 最低售价。 */
    class MinPriceRow {
        private Long productId;
        private Long minSalePrice;

        public Long getProductId() {
            return productId;
        }

        public void setProductId(Long productId) {
            this.productId = productId;
        }

        public Long getMinSalePrice() {
            return minSalePrice;
        }

        public void setMinSalePrice(Long minSalePrice) {
            this.minSalePrice = minSalePrice;
        }
    }
}
