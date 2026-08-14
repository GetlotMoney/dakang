package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallCartItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商城购物车 Mapper（E2E-09 S2）。
 *
 * <p>加购与改量都走 upsert：唯一键 uk(USER_ID,SKU_ID) 不含 DATA_STATUS，移除过的行
 * 必须能被同一语句复活，否则用户第二次加购同一 SKU 会直接撞键报错。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Mapper
public interface WsMallCartItemMapper extends BaseMapper<WsMallCartItem> {

    /**
     * 加购：行不存在即建行，存在即原子累加；已移除的行按新加购重置为 qty
     * （不是在旧数量上累加——用户移除后重新加购，期望看到的是新数量）。
     */
    @Insert("""
            INSERT INTO ws_mall_cart_item
                   (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,
                    USER_ID, SKU_ID, QUANTITY)
            VALUES (0, #{userId}, #{now}, #{userId}, #{now}, #{userId}, #{skuId}, #{qty})
            ON DUPLICATE KEY UPDATE
                   QUANTITY = IF(DATA_STATUS = 1, #{qty}, QUANTITY + #{qty}),
                   DATA_STATUS = 0, UPDATE_BY = #{userId}, UPDATE_TIME = #{now}""")
    int addQuantity(@Param("userId") Long userId, @Param("skuId") Long skuId,
                    @Param("qty") Integer qty, @Param("now") String now);

    /** 购物车页改量：绝对值覆盖；同样复活已移除行。 */
    @Insert("""
            INSERT INTO ws_mall_cart_item
                   (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,
                    USER_ID, SKU_ID, QUANTITY)
            VALUES (0, #{userId}, #{now}, #{userId}, #{now}, #{userId}, #{skuId}, #{qty})
            ON DUPLICATE KEY UPDATE
                   QUANTITY = #{qty}, DATA_STATUS = 0,
                   UPDATE_BY = #{userId}, UPDATE_TIME = #{now}""")
    int setQuantity(@Param("userId") Long userId, @Param("skuId") Long skuId,
                    @Param("qty") Integer qty, @Param("now") String now);

    /** 移除：逻辑删除并按会话人过滤——只有本人能移除本人的行。 */
    @Update("""
            UPDATE ws_mall_cart_item
               SET DATA_STATUS = 1, UPDATE_BY = #{userId}, UPDATE_TIME = #{now}
             WHERE USER_ID = #{userId} AND SKU_ID = #{skuId} AND DATA_STATUS = 0""")
    int removeBySku(@Param("userId") Long userId, @Param("skuId") Long skuId,
                    @Param("now") String now);
}
