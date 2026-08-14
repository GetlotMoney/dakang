package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallPayment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 商城支付单 Mapper（E2E-09 S2）。
 *
 * <p>支付单状态同样走 CAS。TRANSACTION_ID 的唯一索引是"同一笔外部交易不得记到两张
 * 支付单"的库层防线；写入交易号与置成功必须同一条语句完成，避免中间态被别的事实覆盖。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Mapper
public interface WsMallPaymentMapper extends BaseMapper<WsMallPayment> {

    /** 按订单号原样读（绕过逻辑删除过滤）。 */
    @Select("SELECT * FROM ws_mall_payment WHERE ORDER_NO = #{orderNo}")
    WsMallPayment selectByOrderNoIncludingDeleted(@Param("orderNo") String orderNo);

    /**
     * 置支付成功：仅当当前为待支付(1) 时生效，同时落交易号与权威成功时间。
     * 成功时间取自支付事实而非本地时钟——本地时钟会让对账时间线与支付方对不上。
     */
    @Update("""
            UPDATE ws_mall_payment
               SET PAY_STATUS = 2, TRANSACTION_ID = #{transactionId},
                   PAY_SUCCESS_TIME = #{paySuccessTime},
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0 AND PAY_STATUS = 1""")
    int casSuccess(@Param("id") Long id, @Param("transactionId") String transactionId,
                   @Param("paySuccessTime") String paySuccessTime,
                   @Param("operator") Long operator, @Param("now") String now);

    /** 置支付关闭：仅当当前为待支付(1) 时生效。 */
    @Update("""
            UPDATE ws_mall_payment
               SET PAY_STATUS = 4, CLOSE_TIME = #{now},
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0 AND PAY_STATUS = 1""")
    int casClose(@Param("id") Long id, @Param("operator") Long operator, @Param("now") String now);
}
