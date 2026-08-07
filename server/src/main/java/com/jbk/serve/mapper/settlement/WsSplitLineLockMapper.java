package com.jbk.serve.mapper.settlement;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 分账比例配置的商品线锁锚（R1 P1-3）。
 *
 * <p>每商品线恒一行、无业务数据；配置写入事务以 {@code FOR UPDATE} 锁定本行获得
 * <b>跨实例</b>的同线串行化（Java synchronized 只覆盖单实例，明令禁止作最终方案）。
 * 不同商品线各锁各行，互不阻塞。</p>
 *
 * @author dakang
 * @since 2026-08-07
 */
@Mapper
public interface WsSplitLineLockMapper {

    /**
     * 锁定商品线锚行。返回 null=锚行不存在（新库未执行 2026-08-07-split-line-lock 迁移），
     * 调用方必须 fail-closed 拒绝写入而不是退化为无锁校验。
     */
    @Select("SELECT PRODUCT_LINE FROM ws_split_line_lock WHERE PRODUCT_LINE = #{productLine} FOR UPDATE")
    Integer lockLine(@Param("productLine") Integer productLine);
}
