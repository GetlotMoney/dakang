package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.mall.WsMallCartItemMapper;
import com.jbk.serve.mapper.mall.WsMallProductMapper;
import com.jbk.serve.mapper.mall.WsMallSkuMapper;
import com.jbk.serve.service.mall.IMallCartService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.bo.MallCartSaveBo;
import com.jbk.tool.data.mall.po.WsMallCartItem;
import com.jbk.tool.data.mall.po.WsMallProduct;
import com.jbk.tool.data.mall.po.WsMallSku;
import com.jbk.tool.data.mall.vo.MiniMallCartVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商城购物车服务实现（E2E-09 S2）。
 *
 * <p>购物车只记"想买什么、买几件"，不预占库存也不锁价：价格与可售状态都在结算与
 * 创单时重新读取。因此下架商品仍会留在购物车里并标注失效——静默删掉用户加过的东西，
 * 会让人以为是系统丢了数据。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Slf4j
@Service
public class MallCartServiceImpl implements IMallCartService {

    /** 购物车行数上限：防止端上循环加购把单用户购物车撑爆。 */
    private static final int MAX_LINES = 50;

    /** 单行数量上限：与库层 chk_mall_cart_qty_positive 同界，仅用于组织提示文案。 */
    private static final int MAX_QUANTITY_PER_LINE = 999;

    @Autowired
    private WsMallCartItemMapper cartItemMapper;
    @Autowired
    private WsMallSkuMapper skuMapper;
    @Autowired
    private WsMallProductMapper productMapper;

    @Override
    public MiniMallCartVo list(Long userId) {
        List<WsMallCartItem> rows = cartItemMapper.selectList(
                Wrappers.lambdaQuery(WsMallCartItem.class)
                        .eq(WsMallCartItem::getUserId, userId)
                        .orderByDesc(WsMallCartItem::getUpdateTime));
        return assemble(rows);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MiniMallCartVo save(Long userId, MallCartSaveBo bo) {
        WsMallSku sku = skuMapper.selectById(bo.getSkuId());
        if (ObjectUtil.isNull(sku)) {
            throw new JbkException("商品规格不存在");
        }
        WsMallProduct product = productMapper.selectById(sku.getProductId());
        if (ObjectUtil.isNull(product)) {
            throw new JbkException("商品不存在");
        }
        // 加购口只放已上架且启用的：让用户把已下架商品加进购物车，等于在结算页才告诉他不能买
        if (!ObjectUtil.equal(product.getProductStatus(), MallEnum.ProductStatus.PUBLISHED.getValue())
                || !ObjectUtil.equal(sku.getSkuStatus(), MallEnum.SkuStatus.ENABLED.getValue())) {
            throw new JbkException("该商品当前不可购买");
        }
        boolean increment = Boolean.TRUE.equals(bo.getIncrement());
        // 判据是"这次会不会新增一行"，与 increment 无关：覆盖语义同样会建行，
        // 若只在累加分支查容量，端上传 increment=false 就能绕过行数上限无限建行
        if (!existsLine(userId, bo.getSkuId())) {
            requireCapacity(userId);
        }
        String now = DateUtils.time();
        int affected;
        try {
            affected = increment
                    ? cartItemMapper.addQuantity(userId, bo.getSkuId(), bo.getQuantity(), now)
                    : cartItemMapper.setQuantity(userId, bo.getSkuId(), bo.getQuantity(), now);
        }
        catch (DataAccessException dbError) {
            // 上限由 chk_mall_cart_qty_positive 在库层裁决：累加越界与单次越界走同一条闸，
            // 不存在"先读再判"的竞态窗口（两次并发加购各自都不超、合起来超的场景同样被挡）。
            // MySQL 的 CHECK 违例（错误码 3819）不在 Spring 默认错误码映射表里，会落到
            // UncategorizedSQLException，因此按约束名识别而不是按异常类型识别。
            if (isQuantityCapViolation(dbError)) {
                throw new JbkException("购物车中该规格最多 " + MAX_QUANTITY_PER_LINE + " 件");
            }
            throw dbError;
        }
        if (affected < 1) {
            throw new JbkException("购物车更新失败，请重试");
        }
        return list(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MiniMallCartVo remove(Long userId, Long skuId) {
        if (ObjectUtil.isNull(skuId)) {
            throw new JbkException("请选择要移除的商品规格");
        }
        // 影响 0 行说明本就不在车里（或不属于本人）：按幂等处理，不报错也不越权提示
        cartItemMapper.removeBySku(userId, skuId, DateUtils.time());
        return list(userId);
    }

    /** 按约束名识别数量上限违例；其余数据库错误原样上抛，不冒充成业务提示。 */
    private static boolean isQuantityCapViolation(DataAccessException dbError) {
        Throwable cursor = dbError;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && message.contains("chk_mall_cart_qty_positive")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private boolean existsLine(Long userId, Long skuId) {
        return cartItemMapper.selectCount(Wrappers.lambdaQuery(WsMallCartItem.class)
                .eq(WsMallCartItem::getUserId, userId)
                .eq(WsMallCartItem::getSkuId, skuId)) > 0;
    }

    private void requireCapacity(Long userId) {
        long lines = cartItemMapper.selectCount(Wrappers.lambdaQuery(WsMallCartItem.class)
                .eq(WsMallCartItem::getUserId, userId));
        if (lines >= MAX_LINES) {
            throw new JbkException("购物车最多 " + MAX_LINES + " 个规格，请先结算或移除部分商品");
        }
    }

    /** 组装出参：一次批量取 SKU 与商品，避免逐行查询。 */
    private MiniMallCartVo assemble(List<WsMallCartItem> rows) {
        MiniMallCartVo vo = new MiniMallCartVo().setLines(List.of())
                .setTotalQuantity(0).setTotalAmountFen(0L);
        if (rows.isEmpty()) {
            return vo;
        }
        Map<Long, WsMallSku> skus = skuMapper.selectList(Wrappers.lambdaQuery(WsMallSku.class)
                        .in(WsMallSku::getId, rows.stream().map(WsMallCartItem::getSkuId).toList()))
                .stream().collect(Collectors.toMap(WsMallSku::getId, Function.identity()));
        List<Long> productIds = skus.values().stream().map(WsMallSku::getProductId).distinct().toList();
        Map<Long, WsMallProduct> products = productIds.isEmpty() ? Map.of()
                : productMapper.selectList(Wrappers.lambdaQuery(WsMallProduct.class)
                                .in(WsMallProduct::getId, productIds))
                        .stream().collect(Collectors.toMap(WsMallProduct::getId, Function.identity()));

        int totalQuantity = 0;
        long totalAmount = 0L;
        List<MiniMallCartVo.CartLine> lines = new java.util.ArrayList<>(rows.size());
        for (WsMallCartItem row : rows) {
            WsMallSku sku = skus.get(row.getSkuId());
            WsMallProduct product = ObjectUtil.isNull(sku) ? null : products.get(sku.getProductId());
            MiniMallCartVo.CartLine line = new MiniMallCartVo.CartLine()
                    .setSkuId(String.valueOf(row.getSkuId()))
                    .setQuantity(row.getQuantity());
            String blockReason = unavailableReason(sku, product);
            if (blockReason != null) {
                lines.add(line.setProductId(ObjectUtil.isNull(sku) ? null : String.valueOf(sku.getProductId()))
                        .setProductName(ObjectUtil.isNull(product) ? null : product.getProductName())
                        .setSkuName(ObjectUtil.isNull(sku) ? null : sku.getSkuName())
                        .setSpecs(Map.of())
                        .setSalePriceFen(ObjectUtil.isNull(sku) ? 0L : sku.getSalePrice())
                        .setItemAmountFen(0L)
                        .setPurchasable(false)
                        .setUnavailableReason(blockReason));
                continue;
            }
            long itemAmount = MallMoney.multiply(sku.getSalePrice(), row.getQuantity());
            totalQuantity = MallMoney.addCount(totalQuantity, row.getQuantity());
            totalAmount = MallMoney.add(totalAmount, itemAmount);
            lines.add(line.setProductId(String.valueOf(sku.getProductId()))
                    .setProductName(product.getProductName())
                    .setSkuName(sku.getSkuName())
                    .setSpecs(MallSpecSnapshot.decode(sku.getSpecSnap()))
                    .setCoverUrl(product.getCoverUrl())
                    .setSalePriceFen(sku.getSalePrice())
                    .setItemAmountFen(itemAmount)
                    .setPurchasable(true));
        }
        return vo.setLines(lines).setTotalQuantity(totalQuantity).setTotalAmountFen(totalAmount);
    }

    /** 失效原因；可购买时返回 null。文案面向用户，不出现表名与状态码。 */
    private static String unavailableReason(WsMallSku sku, WsMallProduct product) {
        if (ObjectUtil.isNull(sku) || ObjectUtil.isNull(product)) {
            return "商品已下架";
        }
        if (!ObjectUtil.equal(sku.getSkuStatus(), MallEnum.SkuStatus.ENABLED.getValue())) {
            return "该规格已停售";
        }
        if (!ObjectUtil.equal(product.getProductStatus(), MallEnum.ProductStatus.PUBLISHED.getValue())) {
            return "商品已下架";
        }
        return null;
    }
}
