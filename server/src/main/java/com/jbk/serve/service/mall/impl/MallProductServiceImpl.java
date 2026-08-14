package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.mall.WsMallCategoryMapper;
import com.jbk.serve.mapper.mall.WsMallProductMapper;
import com.jbk.serve.mapper.mall.WsMallSkuMapper;
import com.jbk.serve.mapper.mall.WsMallStockMapper;
import com.jbk.serve.mapper.mall.WsMallWarehouseMapper;
import com.jbk.serve.service.mall.IMallProductService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallIdBo;
import com.jbk.tool.data.mall.bo.MallProductBo;
import com.jbk.tool.data.mall.bo.MallQueryBo;
import com.jbk.tool.data.mall.bo.MallShelfBo;
import com.jbk.tool.data.mall.bo.MallSkuItemBo;
import com.jbk.tool.data.mall.po.WsMallCategory;
import com.jbk.tool.data.mall.po.WsMallProduct;
import com.jbk.tool.data.mall.po.WsMallSku;
import com.jbk.tool.data.mall.po.WsMallStock;
import com.jbk.tool.data.mall.po.WsMallWarehouse;
import com.jbk.tool.data.mall.vo.MallProductDetailVo;
import com.jbk.tool.data.mall.vo.MallProductVo;
import com.jbk.tool.data.mall.vo.MallSkuVo;
import com.jbk.tool.data.mall.vo.MallStockVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.satoken.StpKit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商城商品（SPU+SKU）服务实现（E2E-09 S1）。
 *
 * <p>上架三闸（分类启用、存在启用 SKU、存在启用仓可售库存）与 VERSION CAS 的
 * 状态迁移都在本类唯一实现；SPEC_SNAP 只接受扁平 string→string 键值，编解码
 * 唯一入口是 {@link MallSpecSnapshot}（写读同约束，读取 fail-closed）——
 * 禁止任意 JSON 入库，也禁止把损坏快照静默渲染成正常商品（R1-P1-3）。</p>
 */
@Slf4j
@Service
public class MallProductServiceImpl implements IMallProductService {

    @Autowired
    private WsMallProductMapper productMapper;
    @Autowired
    private WsMallSkuMapper skuMapper;
    @Autowired
    private WsMallCategoryMapper categoryMapper;
    @Autowired
    private WsMallStockMapper stockMapper;
    @Autowired
    private WsMallWarehouseMapper warehouseMapper;
    @Autowired
    private IWsDomainEventService domainEventService;

    // ==================== 查询 ====================

    @Override
    public PageDataVo<MallProductVo> page(MallQueryBo bo) {
        Page<WsMallProduct> page = productMapper.selectPage(
                new Page<>(bo.getCurrent() == null ? 1 : Math.max(1, bo.getCurrent()),
                        bo.getSize() == null ? 10 : Math.min(Math.max(1, bo.getSize()), 100)),
                Wrappers.lambdaQuery(WsMallProduct.class)
                        .eq(bo.getCategoryId() != null, WsMallProduct::getCategoryId, bo.getCategoryId())
                        .eq(bo.getStatus() != null, WsMallProduct::getProductStatus, bo.getStatus())
                        .and(ObjectUtil.isNotEmpty(bo.getKeyword()), q -> q
                                .like(WsMallProduct::getProductName, bo.getKeyword())
                                .or().like(WsMallProduct::getProductNo, bo.getKeyword()))
                        .orderByDesc(WsMallProduct::getId));
        Map<Long, String> categoryNames = categoryNames();
        Map<Long, Long> skuCounts = skuCounts(page.getRecords().stream().map(WsMallProduct::getId).toList());
        List<MallProductVo> rows = page.getRecords().stream().map(p -> new MallProductVo()
                .setId(String.valueOf(p.getId()))
                .setProductNo(p.getProductNo())
                .setCategoryId(String.valueOf(p.getCategoryId()))
                .setCategoryName(categoryNames.get(p.getCategoryId()))
                .setProductName(p.getProductName())
                .setProductSubtitle(p.getProductSubtitle())
                .setCoverUrl(p.getCoverUrl())
                .setProductStatus(p.getProductStatus())
                .setVersion(p.getVersion())
                .setSkuCount(skuCounts.getOrDefault(p.getId(), 0L))
                .setCreateTime(p.getCreateTime())).toList();
        return new PageDataVo<>(rows, page.getTotal());
    }

    @Override
    public MallProductDetailVo detail(MallIdBo bo) {
        WsMallProduct product = productMapper.selectById(bo.getId());
        if (ObjectUtil.isNull(product)) {
            throw new JbkException("商品不存在");
        }
        List<WsMallSku> skus = skuMapper.selectList(Wrappers.lambdaQuery(WsMallSku.class)
                .eq(WsMallSku::getProductId, product.getId()).orderByAsc(WsMallSku::getId));
        List<Long> skuIds = skus.stream().map(WsMallSku::getId).toList();
        List<WsMallStock> stocks = skuIds.isEmpty() ? List.of()
                : stockMapper.selectList(Wrappers.lambdaQuery(WsMallStock.class)
                        .in(WsMallStock::getSkuId, skuIds).orderByAsc(WsMallStock::getId));
        Map<Long, WsMallWarehouse> warehouses = warehouseMapper.selectList(
                        Wrappers.lambdaQuery(WsMallWarehouse.class)).stream()
                .collect(Collectors.toMap(WsMallWarehouse::getId, Function.identity()));
        Map<Long, WsMallSku> skuById = skus.stream()
                .collect(Collectors.toMap(WsMallSku::getId, Function.identity()));
        WsMallCategory category = categoryMapper.selectById(product.getCategoryId());
        return new MallProductDetailVo()
                .setId(String.valueOf(product.getId()))
                .setProductNo(product.getProductNo())
                .setCategoryId(String.valueOf(product.getCategoryId()))
                .setCategoryName(ObjectUtil.isNull(category) ? null : category.getCategoryName())
                .setProductName(product.getProductName())
                .setProductSubtitle(product.getProductSubtitle())
                .setCoverUrl(product.getCoverUrl())
                .setProductDesc(product.getProductDesc())
                .setProductStatus(product.getProductStatus())
                .setVersion(product.getVersion())
                .setSkus(skus.stream().map(MallProductServiceImpl::skuVoOf).toList())
                .setStocks(stocks.stream().map(st -> {
                    WsMallWarehouse wh = warehouses.get(st.getWarehouseId());
                    WsMallSku sku = skuById.get(st.getSkuId());
                    return new MallStockVo()
                            .setId(String.valueOf(st.getId()))
                            .setWarehouseId(String.valueOf(st.getWarehouseId()))
                            .setWarehouseName(ObjectUtil.isNull(wh) ? null : wh.getWarehouseName())
                            .setSkuId(String.valueOf(st.getSkuId()))
                            .setSkuNo(ObjectUtil.isNull(sku) ? null : sku.getSkuNo())
                            .setSkuName(ObjectUtil.isNull(sku) ? null : sku.getSkuName())
                            .setProductId(String.valueOf(product.getId()))
                            .setProductName(product.getProductName())
                            .setAvailableQty(st.getAvailableQty())
                            .setReservedQty(st.getReservedQty())
                            .setVersion(st.getVersion())
                            .setUpdateTime(st.getUpdateTime());
                }).toList());
    }

    // ==================== 保存 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long save(MallProductBo bo) {
        if (ObjectUtil.isEmpty(bo.getProductNo())) {
            throw new JbkException("请填写商品编号");
        }
        if (bo.getSkus() == null || bo.getSkus().isEmpty()) {
            throw new JbkException("至少配置一条 SKU");
        }
        requireCategoryExists(bo.getCategoryId());
        WsMallProduct product = new WsMallProduct()
                .setProductNo(bo.getProductNo().trim())
                .setCategoryId(bo.getCategoryId())
                .setProductName(bo.getProductName().trim())
                .setProductSubtitle(bo.getProductSubtitle())
                .setCoverUrl(bo.getCoverUrl())
                .setProductDesc(bo.getProductDesc())
                .setProductStatus(MallEnum.ProductStatus.DRAFT.getValue())
                .setVersion(1);
        try {
            productMapper.insert(product);
        }
        catch (DuplicateKeyException e) {
            throw new JbkException("商品编号已存在（含历史已删除），请更换");
        }
        for (MallSkuItemBo item : bo.getSkus()) {
            insertSku(product.getId(), item);
        }
        return product.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(MallProductBo bo) {
        if (bo.getId() == null) {
            throw new JbkException("缺少商品信息");
        }
        WsMallProduct existed = productMapper.selectById(bo.getId());
        if (ObjectUtil.isNull(existed)) {
            throw new JbkException("商品不存在");
        }
        requireCategoryExists(bo.getCategoryId());
        // 编号不可改；状态不在此改（上下架走 CAS 端点）
        int updated = productMapper.update(null, Wrappers.lambdaUpdate(WsMallProduct.class)
                .eq(WsMallProduct::getId, bo.getId())
                .set(WsMallProduct::getCategoryId, bo.getCategoryId())
                .set(WsMallProduct::getProductName, bo.getProductName().trim())
                .set(WsMallProduct::getProductSubtitle, bo.getProductSubtitle())
                .set(WsMallProduct::getCoverUrl, bo.getCoverUrl())
                .set(WsMallProduct::getProductDesc, bo.getProductDesc()));
        if (updated != 1) {
            throw new JbkException("商品更新失败，请刷新后重试");
        }
        if (bo.getSkus() != null) {
            for (MallSkuItemBo item : bo.getSkus()) {
                if (item.getId() == null) {
                    insertSku(bo.getId(), item);
                }
                else {
                    updateSku(bo.getId(), item);
                }
            }
        }
        return true;
    }

    private void insertSku(Long productId, MallSkuItemBo item) {
        if (ObjectUtil.isEmpty(item.getSkuNo())) {
            throw new JbkException("新增 SKU 必须填写编号");
        }
        validatePrices(item);
        WsMallSku sku = new WsMallSku()
                .setSkuNo(item.getSkuNo().trim())
                .setProductId(productId)
                .setSkuName(item.getSkuName().trim())
                .setSpecSnap(MallSpecSnapshot.encode(item.getSpecs()))
                .setSalePrice(item.getSalePrice())
                .setMarketPrice(item.getMarketPrice())
                .setWeightGram(item.getWeightGram())
                .setSkuStatus(item.getSkuStatus())
                .setVersion(1);
        requireValidSkuStatus(item.getSkuStatus());
        try {
            skuMapper.insert(sku);
        }
        catch (DuplicateKeyException e) {
            throw new JbkException("SKU 编号已存在（含历史已删除）：" + item.getSkuNo());
        }
    }

    private void updateSku(Long productId, MallSkuItemBo item) {
        WsMallSku existed = skuMapper.selectById(item.getId());
        if (ObjectUtil.isNull(existed) || ObjectUtil.notEqual(existed.getProductId(), productId)) {
            throw new JbkException("SKU 不存在或不属于该商品");
        }
        validatePrices(item);
        requireValidSkuStatus(item.getSkuStatus());
        int updated = skuMapper.update(null, Wrappers.lambdaUpdate(WsMallSku.class)
                .eq(WsMallSku::getId, item.getId())
                .set(WsMallSku::getSkuName, item.getSkuName().trim())
                .set(WsMallSku::getSpecSnap, MallSpecSnapshot.encode(item.getSpecs()))
                .set(WsMallSku::getSalePrice, item.getSalePrice())
                .set(WsMallSku::getMarketPrice, item.getMarketPrice())
                .set(WsMallSku::getWeightGram, item.getWeightGram())
                .set(WsMallSku::getSkuStatus, item.getSkuStatus()));
        if (updated != 1) {
            throw new JbkException("SKU 更新失败：" + existed.getSkuNo());
        }
    }

    private static void validatePrices(MallSkuItemBo item) {
        if (item.getMarketPrice() != null && item.getMarketPrice() < item.getSalePrice()) {
            throw new JbkException("划线价不得小于售价：" + item.getSkuName());
        }
    }

    private static void requireValidSkuStatus(Integer status) {
        if (ObjectUtil.notEqual(status, MallEnum.SkuStatus.ENABLED.getValue())
                && ObjectUtil.notEqual(status, MallEnum.SkuStatus.DISABLED.getValue())) {
            throw new JbkException("SKU 状态非法");
        }
    }

    private void requireCategoryExists(Long categoryId) {
        if (ObjectUtil.isNull(categoryMapper.selectById(categoryId))) {
            throw new JbkException("商品分类不存在");
        }
    }

    // ==================== 上下架（VERSION CAS） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean publish(MallShelfBo bo) {
        WsMallProduct product = productMapper.selectById(bo.getId());
        if (ObjectUtil.isNull(product)) {
            throw new JbkException("商品不存在");
        }
        if (ObjectUtil.equal(product.getProductStatus(), MallEnum.ProductStatus.PUBLISHED.getValue())) {
            throw new JbkException("商品已是上架状态");
        }
        // 上架三闸：分类启用 / 存在启用 SKU / 存在启用仓可售库存（口径与小程序聚合同源）
        WsMallCategory category = categoryMapper.selectById(product.getCategoryId());
        if (ObjectUtil.isNull(category)
                || ObjectUtil.notEqual(category.getCategoryStatus(), MallEnum.CategoryStatus.ENABLED.getValue())) {
            throw new JbkException("商品分类已停用，不可上架");
        }
        Long enabledSkus = skuMapper.selectCount(Wrappers.lambdaQuery(WsMallSku.class)
                .eq(WsMallSku::getProductId, product.getId())
                .eq(WsMallSku::getSkuStatus, MallEnum.SkuStatus.ENABLED.getValue()));
        if (enabledSkus == null || enabledSkus == 0) {
            throw new JbkException("商品无启用 SKU，不可上架");
        }
        if (stockMapper.sellableSkuIds(product.getId()).isEmpty()) {
            throw new JbkException("商品在启用前置仓无可售库存，不可上架");
        }
        casStatus(product, bo.getVersion(), MallEnum.ProductStatus.PUBLISHED.getValue(), "上架");
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean unpublish(MallShelfBo bo) {
        WsMallProduct product = productMapper.selectById(bo.getId());
        if (ObjectUtil.isNull(product)) {
            throw new JbkException("商品不存在");
        }
        if (ObjectUtil.notEqual(product.getProductStatus(), MallEnum.ProductStatus.PUBLISHED.getValue())) {
            throw new JbkException("商品不是上架状态");
        }
        casStatus(product, bo.getVersion(), MallEnum.ProductStatus.UNPUBLISHED.getValue(), "下架");
        return true;
    }

    private void casStatus(WsMallProduct product, Integer version, int toStatus, String actionDesc) {
        int updated = productMapper.casStatus(product.getId(), version,
                product.getProductStatus(), toStatus,
                currentOperator(), DateUtils.time());
        if (updated != 1) {
            throw new JbkException("商品状态已变化，请刷新后重试");
        }
        // 可靠审计与状态迁移同事务：上下架是端上可见性的开关，必须留痕
        domainEventService.recordReliableInTx(OpsEnum.EventType.MALL,
                "MALL_PRODUCT:" + product.getId(),
                statusDesc(product.getProductStatus()), actionDesc + "→" + statusDesc(toStatus));
    }


    /** 管理端操作人：无登录上下文（真库测试/内部调用）按系统 0 记录，与 Meta 填充同约。 */
    private static long currentOperator() {
        try {
            return StpKit.MANAGE.getLoginIdAsLong();
        }
        catch (Exception e) {
            return 0L;
        }
    }

    private static String statusDesc(Integer status) {
        for (MallEnum.ProductStatus candidate : MallEnum.ProductStatus.values()) {
            if (ObjectUtil.equal(status, candidate.getValue())) {
                return candidate.getDesc();
            }
        }
        return "未知" + status;
    }

    // ==================== 规格快照（唯一编解码入口 = MallSpecSnapshot） ====================

    static MallSkuVo skuVoOf(WsMallSku sku) {
        // 读取严格 fail-closed（R1-P1-3）：损坏快照整次详情失败，绝不渲染"假规格"商品
        return new MallSkuVo()
                .setId(String.valueOf(sku.getId()))
                .setSkuNo(sku.getSkuNo())
                .setSkuName(sku.getSkuName())
                .setSpecs(MallSpecSnapshot.decode(sku.getSpecSnap()))
                .setSalePrice(sku.getSalePrice())
                .setMarketPrice(sku.getMarketPrice())
                .setWeightGram(sku.getWeightGram())
                .setSkuStatus(sku.getSkuStatus())
                .setVersion(sku.getVersion());
    }

    private Map<Long, String> categoryNames() {
        return categoryMapper.selectList(Wrappers.lambdaQuery(WsMallCategory.class)).stream()
                .collect(Collectors.toMap(WsMallCategory::getId, WsMallCategory::getCategoryName));
    }

    private Map<Long, Long> skuCounts(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return skuMapper.selectList(Wrappers.lambdaQuery(WsMallSku.class)
                        .in(WsMallSku::getProductId, productIds)).stream()
                .collect(Collectors.groupingBy(WsMallSku::getProductId, Collectors.counting()));
    }
}
