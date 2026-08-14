package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.mall.WsMallProductMapper;
import com.jbk.serve.mapper.mall.WsMallSkuMapper;
import com.jbk.serve.mapper.mall.WsMallStockFlowMapper;
import com.jbk.serve.mapper.mall.WsMallStockMapper;
import com.jbk.serve.mapper.mall.WsMallWarehouseMapper;
import com.jbk.serve.service.mall.IMallStockService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallQueryBo;
import com.jbk.tool.data.mall.bo.MallStockAdjustBo;
import com.jbk.tool.data.mall.po.WsMallProduct;
import com.jbk.tool.data.mall.po.WsMallSku;
import com.jbk.tool.data.mall.po.WsMallStock;
import com.jbk.tool.data.mall.po.WsMallStockFlow;
import com.jbk.tool.data.mall.po.WsMallWarehouse;
import com.jbk.tool.data.mall.vo.MallSkuCandidateVo;
import com.jbk.tool.data.mall.vo.MallStockAdjustResultVo;
import com.jbk.tool.data.mall.vo.MallStockFlowVo;
import com.jbk.tool.data.mall.vo.MallStockVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.satoken.StpKit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商城库存服务实现（E2E-09 S1）。原子性：数量变化只走 upsertIncrease/decreaseAvailable
 * 两条原子语句（减量带 AVAILABLE_QTY >= qty 前置，影响行数即裁决），库层命名 CHECK 兜底
 * （R1-P2-1），禁止「SELECT→内存算→回写」。幂等锚=uk_mall_stock_flow_biz_key
 * （MALLADJ:&lt;requestId&gt;，不含 DATA_STATUS——误删不解锁）：命中逐字核一致返回原结果、
 * 漂移拒绝；未命中动账后插流水，并发撞键整事务回滚先写者胜。库存更新与流水同事务
 * （READ_COMMITTED，N-15 先例），拒绝路径零流水零库存变化；多条动作按 (WAREHOUSE_ID,SKU_ID)
 * 升序处理（S2 预占沿用）。
 */
@Slf4j
@Service
public class MallStockServiceImpl implements IMallStockService {

    private static final String KEY_PREFIX = "MALLADJ:";

    @Autowired
    private WsMallStockMapper stockMapper;
    @Autowired
    private WsMallStockFlowMapper flowMapper;
    @Autowired
    private WsMallWarehouseMapper warehouseMapper;
    @Autowired
    private WsMallSkuMapper skuMapper;
    @Autowired
    private WsMallProductMapper productMapper;
    @Autowired
    private IWsDomainEventService domainEventService;

    // ==================== 查询 ====================

    @Override
    public PageDataVo<MallStockVo> page(MallQueryBo bo) {
        List<Long> skuFilter = skuIdsForStockQuery(bo);
        if (skuFilter != null && skuFilter.isEmpty()) {
            return new PageDataVo<>(List.of(), 0L);
        }
        Page<WsMallStock> page = stockMapper.selectPage(
                new Page<>(bo.getCurrent() == null ? 1 : Math.max(1, bo.getCurrent()),
                        bo.getSize() == null ? 10 : Math.min(Math.max(1, bo.getSize()), 100)),
                Wrappers.lambdaQuery(WsMallStock.class)
                        .eq(bo.getWarehouseId() != null, WsMallStock::getWarehouseId, bo.getWarehouseId())
                        .in(skuFilter != null, WsMallStock::getSkuId, skuFilter)
                        .orderByDesc(WsMallStock::getId));
        return new PageDataVo<>(stockVosOf(page.getRecords()), page.getTotal());
    }

    /** 分类/商品/SKU 关键字筛选折算成 SKU ID 集：null=不过滤；空集=零结果。 */
    private List<Long> skuIdsForStockQuery(MallQueryBo bo) {
        boolean byCategory = bo.getCategoryId() != null;
        boolean byProduct = bo.getProductId() != null;
        boolean byKeyword = ObjectUtil.isNotEmpty(bo.getKeyword());
        if (!byCategory && !byProduct && !byKeyword) {
            return null;
        }
        List<Long> productIds = null;
        if (byCategory || byProduct) {
            productIds = productMapper.selectList(Wrappers.lambdaQuery(WsMallProduct.class)
                            .eq(byCategory, WsMallProduct::getCategoryId, bo.getCategoryId())
                            .eq(byProduct, WsMallProduct::getId, bo.getProductId()))
                    .stream().map(WsMallProduct::getId).toList();
            if (productIds.isEmpty()) {
                return List.of();
            }
        }
        return skuMapper.selectList(Wrappers.lambdaQuery(WsMallSku.class)
                        .in(productIds != null, WsMallSku::getProductId, productIds)
                        .and(byKeyword, q -> q.like(WsMallSku::getSkuName, bo.getKeyword())
                                .or().like(WsMallSku::getSkuNo, bo.getKeyword())))
                .stream().map(WsMallSku::getId).toList();
    }

    /**
     * 库存动作 SKU 候选（R2-P0）：数据源恒为 SKU⋈商品，绝不从库存行反推——新建
     * SKU 尚无 ws_mall_stock 行时也必须可选，否则首次入库无入口、纵向链断裂。
     * 停用 SKU 照常返回（带状态标识）：动作既有语义只要求 SKU 存在，不新增禁调规则。
     * 关键字远程搜索（SKU 编号/名称），单页硬上限 50，禁无上限全量拉取。
     */
    @Override
    public PageDataVo<MallSkuCandidateVo> skuCandidates(MallQueryBo bo) {
        Page<WsMallSku> page = skuMapper.selectPage(
                new Page<>(bo.getCurrent() == null ? 1 : Math.max(1, bo.getCurrent()),
                        bo.getSize() == null ? 20 : Math.min(Math.max(1, bo.getSize()), 50)),
                Wrappers.lambdaQuery(WsMallSku.class)
                        .eq(bo.getProductId() != null, WsMallSku::getProductId, bo.getProductId())
                        .and(ObjectUtil.isNotEmpty(bo.getKeyword()), q -> q
                                .like(WsMallSku::getSkuName, bo.getKeyword())
                                .or().like(WsMallSku::getSkuNo, bo.getKeyword()))
                        .orderByDesc(WsMallSku::getId));
        Map<Long, String> productNames = productMapper.selectList(
                        Wrappers.lambdaQuery(WsMallProduct.class)).stream()
                .collect(Collectors.toMap(WsMallProduct::getId, WsMallProduct::getProductName));
        List<MallSkuCandidateVo> rows = page.getRecords().stream().map(sku -> new MallSkuCandidateVo()
                .setSkuId(String.valueOf(sku.getId()))
                .setSkuNo(sku.getSkuNo())
                .setSkuName(sku.getSkuName())
                .setProductId(String.valueOf(sku.getProductId()))
                .setProductName(productNames.get(sku.getProductId()))
                .setSkuStatus(sku.getSkuStatus())).toList();
        return new PageDataVo<>(rows, page.getTotal());
    }

    @Override
    public PageDataVo<MallStockFlowVo> flowPage(MallQueryBo bo) {
        Page<WsMallStockFlow> page = flowMapper.selectPage(
                new Page<>(bo.getCurrent() == null ? 1 : Math.max(1, bo.getCurrent()),
                        bo.getSize() == null ? 10 : Math.min(Math.max(1, bo.getSize()), 100)),
                Wrappers.lambdaQuery(WsMallStockFlow.class)
                        .eq(bo.getWarehouseId() != null, WsMallStockFlow::getWarehouseId, bo.getWarehouseId())
                        .eq(bo.getSkuId() != null, WsMallStockFlow::getSkuId, bo.getSkuId())
                        .orderByDesc(WsMallStockFlow::getId));
        Map<Long, String> warehouseNames = warehouseNames();
        Map<Long, String> skuNames = skuNames();
        List<MallStockFlowVo> rows = page.getRecords().stream().map(f -> new MallStockFlowVo()
                .setId(String.valueOf(f.getId()))
                .setRequestId(f.getBizIdempotencyKey() != null
                        && f.getBizIdempotencyKey().startsWith(KEY_PREFIX)
                        ? f.getBizIdempotencyKey().substring(KEY_PREFIX.length())
                        : f.getBizIdempotencyKey())
                .setWarehouseId(String.valueOf(f.getWarehouseId()))
                .setWarehouseName(warehouseNames.get(f.getWarehouseId()))
                .setSkuId(String.valueOf(f.getSkuId()))
                .setSkuName(skuNames.get(f.getSkuId()))
                .setFlowType(f.getFlowType())
                .setAvailableChange(f.getAvailableChange())
                .setReservedChange(f.getReservedChange())
                .setAvailableAfter(f.getAvailableAfter())
                .setReservedAfter(f.getReservedAfter())
                .setFlowReason(f.getFlowReason())
                .setOperatorId(String.valueOf(f.getOperatorId()))
                .setCreateTime(f.getCreateTime())).toList();
        return new PageDataVo<>(rows, page.getTotal());
    }

    // ==================== 人工库存动作 ====================

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MallStockAdjustResultVo adjust(MallStockAdjustBo bo) {
        MallEnum.StockFlowType flowType = MallEnum.StockFlowType.getByValue(bo.getFlowType());
        if (flowType == null || !flowType.isManual()) {
            // 5~8（预占/释放/实销/回库）只能由订单链路在服务内部写入：人工端点一旦放行，
            // 就能绕过订单凭空制造预占或实销，库存与订单从此对不上账
            throw new JbkException("动作类型非法（人工端点仅支持入库/出库/盘点调整）");
        }
        String bizKey = KEY_PREFIX + bo.getRequestId();
        // 幂等预读（快路径，正确性锚在唯一键）：原样读绕过逻辑删过滤——误删流水仍占键
        WsMallStockFlow existed = flowMapper.selectByKeyIncludingDeleted(bizKey);
        if (ObjectUtil.isNotNull(existed)) {
            boolean same = ObjectUtil.equal(existed.getWarehouseId(), bo.getWarehouseId())
                    && ObjectUtil.equal(existed.getSkuId(), bo.getSkuId())
                    && ObjectUtil.equal(existed.getFlowType(), bo.getFlowType())
                    && Math.abs(existed.getAvailableChange()) == bo.getQuantity();
            if (!same) {
                throw new JbkException("同请求号的参数与原记录不一致，已拒绝（请更换请求号）");
            }
            // 同参数重放：从原流水行构造冻结结果，零副作用。禁止读当前库存冒充——
            // 原动作与重放之间若有其他动作，当前值≠原动作后置值（R1-P1-2）
            return resultVoOf(existed);
        }
        requireWarehouseExists(bo.getWarehouseId());
        requireSkuExists(bo.getSkuId());
        long operator = currentOperator();
        String now = DateUtils.time();
        if (flowType.isIncrease()) {
            // 加量=单条原子 upsert（建行或累加一步完成）。禁止「先盲 INSERT 撞键再 UPDATE」：
            // 撞键失败的 INSERT 在既有行留下共享锁、随后升级排它锁，两个并发动作即
            // S+S→X 经典死锁（真库并发用例实测复现过）
            stockMapper.upsertIncrease(bo.getWarehouseId(), bo.getSkuId(),
                    bo.getQuantity(), operator, now);
        }
        else {
            // 减量=原子条件 UPDATE：行不存在或可售不足都=0 行，一律按不足拒绝——
            // 抛出→整事务回滚，零流水零库存变化
            int updated = stockMapper.decreaseAvailable(bo.getWarehouseId(), bo.getSkuId(),
                    bo.getQuantity(), operator, now);
            if (updated != 1) {
                throw new JbkException("可售库存不足，" + flowType.getDesc() + "被拒绝");
            }
        }
        WsMallStock after = stockMapper.selectOne(Wrappers.lambdaQuery(WsMallStock.class)
                .eq(WsMallStock::getWarehouseId, bo.getWarehouseId())
                .eq(WsMallStock::getSkuId, bo.getSkuId()));
        if (ObjectUtil.isNull(after)) {
            throw new JbkException("库存行读取失败，动作中止");
        }
        long change = flowType.isIncrease() ? bo.getQuantity() : -bo.getQuantity();
        WsMallStockFlow flow = new WsMallStockFlow()
                .setBizIdempotencyKey(bizKey)
                .setWarehouseId(bo.getWarehouseId())
                .setSkuId(bo.getSkuId())
                .setFlowType(flowType.getValue())
                .setAvailableChange(change)
                .setReservedChange(0L)
                .setAvailableAfter(after.getAvailableQty())
                .setReservedAfter(after.getReservedQty())
                .setFlowReason(bo.getReason().trim())
                .setOperatorId(operator);
        // 业务动作时间与库存终值同源；其余审计字段仍由 BaseEntity 的填充策略统一写入。
        flow.setCreateTime(now);
        try {
            flowMapper.insert(flow);
        }
        catch (DuplicateKeyException race) {
            // 并发同请求号：唯一键裁决，后到者整事务回滚（含上面的库存更新），先写者胜
            throw new JbkException("同请求号动作正在处理或已完成，请勿并发重放");
        }
        // 可靠审计与库存动作同事务（1363#11 商城动作）
        domainEventService.recordReliableInTx(OpsEnum.EventType.MALL,
                "MALLSTOCK:" + bo.getWarehouseId() + ":" + bo.getSkuId(),
                flowType.getDesc() + " " + bo.getQuantity() + " 件",
                "可售 " + after.getAvailableQty() + " 件");
        // 首次也从流水行构造：与重放路径同一来源，结果口径永不分叉
        return resultVoOf(flow);
    }

    /** 动作结果统一从幂等流水行构造（首次=刚插入的行，重放=命中的原行）。 */
    private static MallStockAdjustResultVo resultVoOf(WsMallStockFlow flow) {
        String bizKey = flow.getBizIdempotencyKey();
        return new MallStockAdjustResultVo()
                .setRequestId(bizKey != null && bizKey.startsWith(KEY_PREFIX)
                        ? bizKey.substring(KEY_PREFIX.length())
                        : bizKey)
                .setWarehouseId(String.valueOf(flow.getWarehouseId()))
                .setSkuId(String.valueOf(flow.getSkuId()))
                .setFlowType(flow.getFlowType())
                .setAvailableChange(flow.getAvailableChange())
                .setAvailableAfter(flow.getAvailableAfter())
                .setReservedAfter(flow.getReservedAfter());
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

    private void requireWarehouseExists(Long warehouseId) {
        if (ObjectUtil.isNull(warehouseMapper.selectById(warehouseId))) {
            throw new JbkException("前置仓不存在");
        }
    }

    private void requireSkuExists(Long skuId) {
        if (ObjectUtil.isNull(skuMapper.selectById(skuId))) {
            throw new JbkException("SKU 不存在");
        }
    }

    // ==================== 视图组装 ====================

    private List<MallStockVo> stockVosOf(List<WsMallStock> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        Map<Long, String> warehouseNames = warehouseNames();
        Map<Long, WsMallSku> skus = skuMapper.selectList(Wrappers.lambdaQuery(WsMallSku.class)
                        .in(WsMallSku::getId, records.stream().map(WsMallStock::getSkuId).toList()))
                .stream().collect(Collectors.toMap(WsMallSku::getId, Function.identity()));
        Map<Long, String> productNames = productMapper.selectList(
                        Wrappers.lambdaQuery(WsMallProduct.class)).stream()
                .collect(Collectors.toMap(WsMallProduct::getId, WsMallProduct::getProductName));
        return records.stream().map(st -> {
            WsMallSku sku = skus.get(st.getSkuId());
            MallStockVo vo = stockVoOf(st).setWarehouseName(warehouseNames.get(st.getWarehouseId()));
            if (ObjectUtil.isNotNull(sku)) {
                vo.setSkuNo(sku.getSkuNo()).setSkuName(sku.getSkuName())
                        .setProductId(String.valueOf(sku.getProductId()))
                        .setProductName(productNames.get(sku.getProductId()));
            }
            return vo;
        }).toList();
    }

    private static MallStockVo stockVoOf(WsMallStock row) {
        return new MallStockVo()
                .setId(String.valueOf(row.getId()))
                .setWarehouseId(String.valueOf(row.getWarehouseId()))
                .setSkuId(String.valueOf(row.getSkuId()))
                .setAvailableQty(row.getAvailableQty())
                .setReservedQty(row.getReservedQty())
                .setVersion(row.getVersion())
                .setUpdateTime(row.getUpdateTime());
    }

    private Map<Long, String> warehouseNames() {
        return warehouseMapper.selectList(Wrappers.lambdaQuery(WsMallWarehouse.class)).stream()
                .collect(Collectors.toMap(WsMallWarehouse::getId, WsMallWarehouse::getWarehouseName));
    }

    private Map<Long, String> skuNames() {
        return skuMapper.selectList(Wrappers.lambdaQuery(WsMallSku.class)).stream()
                .collect(Collectors.toMap(WsMallSku::getId, WsMallSku::getSkuName));
    }
}
