package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商城库存 Mapper（E2E-09 S1 人工动作 + S2 订单流转）。
 *
 * <p>一切数量变化只允许本接口的原子条件 UPDATE：定位键=uk(WAREHOUSE_ID,SKU_ID)，
 * 减量携带 {@code >= qty} 前置条件——影响行数即裁决，负库存另有命名 CHECK 兜底。
 * 禁止「SELECT 数量→Java 计算→UPDATE 回写」。</p>
 *
 * <p>多 SKU 场景下调用方必须按 (WAREHOUSE_ID, SKU_ID) 升序逐条调用：两个订单
 * 争抢同两个 SKU 时，只要加锁顺序一致就不会互等成环。</p>
 *
 * <p>退货回库（流水类型8）随 S4 售后落地，本接口暂不提供——没有售后单的回库
 * 动作无法核对来源，提前放出等于开了一个凭空加库存的口子。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Mapper
public interface WsMallStockMapper extends BaseMapper<WsMallStock> {

    /**
     * 原子加量（入库/盘点调增）：单条 upsert。刻意不用「先 INSERT 撞键再 UPDATE」两步：撞键 INSERT 留共享锁再升级排它锁即 S+S→X 死锁；
     * ON DUPLICATE KEY UPDATE 一步拿排它锁，无升级窗口。
     */
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO ws_mall_stock
                   (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,
                    WAREHOUSE_ID, SKU_ID, AVAILABLE_QTY, RESERVED_QTY, VERSION)
            VALUES (0, #{operator}, #{now}, #{operator}, #{now},
                    #{warehouseId}, #{skuId}, #{qty}, 0, 1)
            ON DUPLICATE KEY UPDATE
                   AVAILABLE_QTY = AVAILABLE_QTY + #{qty}, VERSION = VERSION + 1,
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}""")
    int upsertIncrease(@Param("warehouseId") Long warehouseId, @Param("skuId") Long skuId,
                       @Param("qty") Long qty, @Param("operator") Long operator,
                       @Param("now") String now);

    /** 原子减量（出库/盘点调减）：条件不满足=0 行，调用方拒绝，绝不硬扣。 */
    @Update("""
            UPDATE ws_mall_stock
               SET AVAILABLE_QTY = AVAILABLE_QTY - #{qty}, VERSION = VERSION + 1,
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE WAREHOUSE_ID = #{warehouseId} AND SKU_ID = #{skuId} AND DATA_STATUS = 0
               AND AVAILABLE_QTY >= #{qty}""")
    int decreaseAvailable(@Param("warehouseId") Long warehouseId, @Param("skuId") Long skuId,
                          @Param("qty") Long qty, @Param("operator") Long operator,
                          @Param("now") String now);

    /**
     * 原子预占（下单，流水类型5）：可售→预占等量迁移。AVAILABLE_QTY >= qty 是唯一裁决——0 行即库存不足（含行不存在），整单回滚，不允许部分预占。
     */
    @Update("""
            UPDATE ws_mall_stock
               SET AVAILABLE_QTY = AVAILABLE_QTY - #{qty}, RESERVED_QTY = RESERVED_QTY + #{qty},
                   VERSION = VERSION + 1, UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE WAREHOUSE_ID = #{warehouseId} AND SKU_ID = #{skuId} AND DATA_STATUS = 0
               AND AVAILABLE_QTY >= #{qty}""")
    int reserveAtomic(@Param("warehouseId") Long warehouseId, @Param("skuId") Long skuId,
                      @Param("qty") Long qty, @Param("operator") Long operator,
                      @Param("now") String now);

    /**
     * 原子释放（取消/超时关闭，流水类型6）：预占→可售等量回迁。RESERVED_QTY >= qty 防重复释放凭空造货；0 行按已释放/证据异常处理，不得硬加。
     */
    @Update("""
            UPDATE ws_mall_stock
               SET AVAILABLE_QTY = AVAILABLE_QTY + #{qty}, RESERVED_QTY = RESERVED_QTY - #{qty},
                   VERSION = VERSION + 1, UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE WAREHOUSE_ID = #{warehouseId} AND SKU_ID = #{skuId} AND DATA_STATUS = 0
               AND RESERVED_QTY >= #{qty}""")
    int releaseAtomic(@Param("warehouseId") Long warehouseId, @Param("skuId") Long skuId,
                      @Param("qty") Long qty, @Param("operator") Long operator,
                      @Param("now") String now);

    /**
     * 原子实销（支付成功，流水类型7）：预占核销出库，只扣 RESERVED——误扣 AVAILABLE 会让同一批货被卖两次。
     */
    @Update("""
            UPDATE ws_mall_stock
               SET RESERVED_QTY = RESERVED_QTY - #{qty},
                   VERSION = VERSION + 1, UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE WAREHOUSE_ID = #{warehouseId} AND SKU_ID = #{skuId} AND DATA_STATUS = 0
               AND RESERVED_QTY >= #{qty}""")
    int sellAtomic(@Param("warehouseId") Long warehouseId, @Param("skuId") Long skuId,
                   @Param("qty") Long qty, @Param("operator") Long operator,
                   @Param("now") String now);

    /** 有可售库存（启用仓、可售>0、启用 SKU）的商品ID集——小程序有货判定唯一口径。 */
    @Select("""
            SELECT DISTINCT s.PRODUCT_ID
              FROM ws_mall_stock st
              JOIN ws_mall_sku s ON s.ID = st.SKU_ID AND s.SKU_STATUS = 1 AND s.DATA_STATUS = 0
              JOIN ws_mall_warehouse w ON w.ID = st.WAREHOUSE_ID
                   AND w.WAREHOUSE_STATUS = 1 AND w.DATA_STATUS = 0
             WHERE st.AVAILABLE_QTY > 0 AND st.DATA_STATUS = 0""")
    List<Long> sellableProductIds();

    /** 指定商品下有可售库存的 SKU ID 集（口径同上）。 */
    @Select("""
            SELECT DISTINCT st.SKU_ID
              FROM ws_mall_stock st
              JOIN ws_mall_sku s ON s.ID = st.SKU_ID AND s.SKU_STATUS = 1 AND s.DATA_STATUS = 0
              JOIN ws_mall_warehouse w ON w.ID = st.WAREHOUSE_ID
                   AND w.WAREHOUSE_STATUS = 1 AND w.DATA_STATUS = 0
             WHERE s.PRODUCT_ID = #{productId} AND st.AVAILABLE_QTY > 0 AND st.DATA_STATUS = 0""")
    List<Long> sellableSkuIds(@Param("productId") Long productId);

    /** 退货回库：只加可售，不碰预占。换货三动作刻意复用 reserveAtomic/sellAtomic/releaseAtomic，差别只在流水类型与幂等键。 */
    @Update("""
            UPDATE ws_mall_stock
               SET AVAILABLE_QTY = AVAILABLE_QTY + #{quantity}, VERSION = VERSION + 1,
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE WAREHOUSE_ID = #{warehouseId} AND SKU_ID = #{skuId} AND DATA_STATUS = 0""")
    int restockAtomic(@Param("warehouseId") Long warehouseId, @Param("skuId") Long skuId,
                      @Param("quantity") long quantity, @Param("operator") Long operator,
                      @Param("now") String now);
}
