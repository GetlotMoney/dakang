package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallRefund;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商城退款单 Mapper（E2E-09 S4）。
 *
 * <p>置成功与写交易号必须同一条语句：分两步会让中间态被另一笔事实覆盖，
 * 出现「已成功但没有交易号」这种事后无法对账的行。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Mapper
public interface WsMallRefundMapper extends BaseMapper<WsMallRefund> {

    @Select("SELECT * FROM ws_mall_refund WHERE REFUND_NO = #{no}")
    WsMallRefund selectByNoIncludingDeleted(@Param("no") String no);

    @Select("SELECT * FROM ws_mall_refund WHERE AFTER_SALE_ID = #{afterSaleId}")
    WsMallRefund selectByAfterSaleIncludingDeleted(@Param("afterSaleId") Long afterSaleId);

    /** 置退款成功：仅当当前为待退款(1) 时生效，同时落交易号与渠道成功时间。 */
    @Update("""
            UPDATE ws_mall_refund
               SET REFUND_STATUS = 2, REFUND_TRANSACTION_ID = #{transactionId},
                   REFUND_SUCCESS_TIME = #{successTime},
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0 AND REFUND_STATUS = 1""")
    int casSuccess(@Param("id") Long id, @Param("transactionId") String transactionId,
                   @Param("successTime") String successTime,
                   @Param("operator") Long operator, @Param("now") String now);

    /** 关闭退款单（售后驳回/取消时）。 */
    @Update("""
            UPDATE ws_mall_refund
               SET REFUND_STATUS = 4, UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0 AND REFUND_STATUS = 1""")
    int casClose(@Param("id") Long id, @Param("operator") Long operator, @Param("now") String now);
}
