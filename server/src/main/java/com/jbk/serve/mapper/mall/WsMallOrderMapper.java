package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商城订单 Mapper（E2E-09 S2）。
 *
 * <p>状态迁移一律走 CAS：{@code WHERE ID=? AND ORDER_STATUS=?}（必要时叠加 VERSION），
 * 影响行数即裁决。"支付成功"与"用户取消"并发时，两条 CAS 只有一条能命中前置状态，
 * 后到者拿到 0 行并按已终态处理——终态由数据库裁决，不由应用层先读后判。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Mapper
public interface WsMallOrderMapper extends BaseMapper<WsMallOrder> {

    /** 按订单号原样读（绕过逻辑删除过滤）：幂等重放与事实关联的权威入口。 */
    @Select("SELECT * FROM ws_mall_order WHERE ORDER_NO = #{orderNo}")
    WsMallOrder selectByOrderNoIncludingDeleted(@Param("orderNo") String orderNo);

    /** 按用户+请求号原样读：创单幂等预读快路径（正确性锚仍是唯一键）。 */
    @Select("SELECT * FROM ws_mall_order WHERE USER_ID = #{userId} AND REQUEST_ID = #{requestId}")
    WsMallOrder selectByUserRequestIncludingDeleted(@Param("userId") Long userId,
                                                    @Param("requestId") String requestId);

    /** 状态 CAS：仅当当前状态等于 fromStatus 时迁移，返回影响行数。 */
    @Update("""
            UPDATE ws_mall_order
               SET ORDER_STATUS = #{toStatus}, VERSION = VERSION + 1,
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0 AND ORDER_STATUS = #{fromStatus}""")
    int casStatus(@Param("id") Long id, @Param("fromStatus") Integer fromStatus,
                  @Param("toStatus") Integer toStatus, @Param("operator") Long operator,
                  @Param("now") String now);

    /** 取消/关闭 CAS：同时落取消时间与原因，便于台账区分用户取消与超时关闭。 */
    @Update("""
            UPDATE ws_mall_order
               SET ORDER_STATUS = #{toStatus}, CANCEL_TIME = #{now}, CANCEL_REASON = #{reason},
                   VERSION = VERSION + 1, UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0 AND ORDER_STATUS = #{fromStatus}""")
    int casCancel(@Param("id") Long id, @Param("fromStatus") Integer fromStatus,
                  @Param("toStatus") Integer toStatus, @Param("reason") String reason,
                  @Param("operator") Long operator, @Param("now") String now);

    /** 用户侧取消 CAS：状态条件外再压归属谓词（纵深防御，库层挡住替他人取消）。 */
    @Update("""
            UPDATE ws_mall_order
               SET ORDER_STATUS = #{toStatus}, CANCEL_TIME = #{now}, CANCEL_REASON = #{reason},
                   VERSION = VERSION + 1, UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND USER_ID = #{userId} AND DATA_STATUS = 0
               AND ORDER_STATUS = #{fromStatus}""")
    int casCancelByOwner(@Param("id") Long id, @Param("userId") Long userId,
                         @Param("fromStatus") Integer fromStatus, @Param("toStatus") Integer toStatus,
                         @Param("reason") String reason, @Param("operator") Long operator,
                         @Param("now") String now);

    /**
     * 超时关单 Worker 扫描：待支付且已过付款截止时间的订单号。
     * 只做宽松预筛——是否真的关闭由 Pay-Sim 权威查单裁决，本地时间不构成关闭依据。
     */
    @Select("""
            SELECT ORDER_NO FROM ws_mall_order
             WHERE DATA_STATUS = 0 AND ORDER_STATUS = 1 AND PAY_EXPIRE_TIME < #{now}
             ORDER BY ID LIMIT #{batch}""")
    List<String> scanExpiredPendingOrderNos(@Param("now") String now, @Param("batch") int batch);

    /** 按换货来源售后单查补发订单（唯一键保证至多一张）。 */
    @Select("SELECT * FROM ws_mall_order WHERE SOURCE_AFTER_SALE_ID = #{afterSaleId}")
    WsMallOrder selectBySourceAfterSale(@Param("afterSaleId") Long afterSaleId);

    /**
     * 行锁读订单：售后数量累计上限「处理中+已成功 ≤ 购买数量」没有唯一键可表达，必须串行化同一订单的申请——
     * 不锁则并发申请各读到「还剩 1 件可退」，同一件货退两次。
     */
    @Select("SELECT * FROM ws_mall_order WHERE ORDER_NO = #{orderNo} FOR UPDATE")
    WsMallOrder selectByOrderNoForUpdate(@Param("orderNo") String orderNo);
}
