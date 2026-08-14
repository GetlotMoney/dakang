package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.mall.WsMallCategoryMapper;
import com.jbk.serve.mapper.mall.WsMallProductMapper;
import com.jbk.serve.mapper.mall.WsMallSkuMapper;
import com.jbk.serve.mapper.mall.WsMallStockMapper;
import com.jbk.serve.service.mall.IMiniMallService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.bo.MallIdBo;
import com.jbk.tool.data.mall.bo.MiniMallHomeBo;
import com.jbk.tool.data.mall.po.WsMallCategory;
import com.jbk.tool.data.mall.po.WsMallProduct;
import com.jbk.tool.data.mall.po.WsMallSku;
import com.jbk.tool.data.mall.vo.MiniMallHomeVo;
import com.jbk.tool.data.mall.vo.MiniMallProductDetailVo;
import com.jbk.tool.exception.JbkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 小程序商城只读服务实现（E2E-09 S1）。
 *
 * <p>端上可见性三闸与上架闸同源：已上架商品、启用 SKU、启用仓可售聚合。
 * 白名单出参——电话原文、库存流水、内部数量、成本、删除标记、范围 JSON
 * 一概不下发（库存只给有货/缺货布尔）。数据源异常直接抛出 fail-closed，
 * 端上不回退 Mock 商品。</p>
 */
@Slf4j
@Service
public class MiniMallServiceImpl implements IMiniMallService {

    @Autowired
    private WsMallCategoryMapper categoryMapper;
    @Autowired
    private WsMallProductMapper productMapper;
    @Autowired
    private WsMallSkuMapper skuMapper;
    @Autowired
    private WsMallStockMapper stockMapper;

    @Override
    public MiniMallHomeVo home(MiniMallHomeBo bo) {
        List<WsMallCategory> categories = categoryMapper.selectList(
                Wrappers.lambdaQuery(WsMallCategory.class)
                        .eq(WsMallCategory::getCategoryStatus, MallEnum.CategoryStatus.ENABLED.getValue())
                        .orderByAsc(WsMallCategory::getCategorySort)
                        .orderByAsc(WsMallCategory::getId));
        List<WsMallProduct> products = productMapper.selectList(
                Wrappers.lambdaQuery(WsMallProduct.class)
                        .eq(WsMallProduct::getProductStatus, MallEnum.ProductStatus.PUBLISHED.getValue())
                        .eq(bo.getCategoryId() != null, WsMallProduct::getCategoryId, bo.getCategoryId())
                        .orderByDesc(WsMallProduct::getId));
        // 有货判定与最低价：集合式两查，绝无逐商品 N+1；口径=启用 SKU×启用仓×可售>0
        Set<Long> sellable = new HashSet<>(stockMapper.sellableProductIds());
        Map<Long, Long> minPrices = skuMapper.minSalePriceByProduct().stream()
                .collect(Collectors.toMap(WsMallSkuMapper.MinPriceRow::getProductId,
                        WsMallSkuMapper.MinPriceRow::getMinSalePrice));
        return new MiniMallHomeVo()
                .setCategories(categories.stream().map(c -> new MiniMallHomeVo.CategoryItem()
                        .setCategoryId(String.valueOf(c.getId()))
                        .setCategoryName(c.getCategoryName())).toList())
                .setProducts(products.stream().map(p -> new MiniMallHomeVo.ProductCard()
                        .setProductId(String.valueOf(p.getId()))
                        .setProductName(p.getProductName())
                        .setProductSubtitle(p.getProductSubtitle())
                        .setCoverUrl(p.getCoverUrl())
                        .setCategoryId(String.valueOf(p.getCategoryId()))
                        .setMinSalePriceFen(minPrices.get(p.getId()))
                        .setInStock(sellable.contains(p.getId()))).toList());
    }

    @Override
    public MiniMallProductDetailVo productDetail(MallIdBo bo) {
        WsMallProduct product = productMapper.selectById(bo.getId());
        // 非上架商品对端上等同不存在：不泄露草稿/下架内容
        if (ObjectUtil.isNull(product) || ObjectUtil.notEqual(product.getProductStatus(),
                MallEnum.ProductStatus.PUBLISHED.getValue())) {
            throw new JbkException("商品不存在或已下架");
        }
        List<WsMallSku> skus = skuMapper.selectList(Wrappers.lambdaQuery(WsMallSku.class)
                .eq(WsMallSku::getProductId, product.getId())
                .eq(WsMallSku::getSkuStatus, MallEnum.SkuStatus.ENABLED.getValue())
                .orderByAsc(WsMallSku::getId));
        Set<Long> sellableSkus = new HashSet<>(stockMapper.sellableSkuIds(product.getId()));
        List<MiniMallProductDetailVo.SkuItem> skuItems = skus.stream()
                .map(sku -> new MiniMallProductDetailVo.SkuItem()
                        .setSkuId(String.valueOf(sku.getId()))
                        .setSkuName(sku.getSkuName())
                        .setSpecs(MallSpecSnapshot.decode(sku.getSpecSnap()))
                        .setSalePriceFen(sku.getSalePrice())
                        .setMarketPriceFen(sku.getMarketPrice())
                        .setWeightGram(sku.getWeightGram())
                        .setInStock(sellableSkus.contains(sku.getId()))).toList();
        return new MiniMallProductDetailVo()
                .setProductId(String.valueOf(product.getId()))
                .setProductName(product.getProductName())
                .setProductSubtitle(product.getProductSubtitle())
                .setCoverUrl(product.getCoverUrl())
                .setProductDesc(product.getProductDesc())
                .setInStock(!sellableSkus.isEmpty())
                .setSkus(skuItems);
    }
}
