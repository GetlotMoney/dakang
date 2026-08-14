package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallAfterSale;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商城售后主单 Mapper（E2E-09 S4）。
 *
 * <p>状态推进一律「精确前态 + 版本号」CAS 并判影响行数。累计量查询刻意排除已驳回/已取消：
 * 那两种终态没有实际发生退款或退货，把它们算进累计会让用户永远退不满。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Mapper
public interface WsMallAfterSaleMapper extends BaseMapper<WsMallAfterSale> {

    /** 按单号原样读（绕过逻辑删除过滤）：让「被删了」成为可判定事实。 */
    @Select("SELECT * FROM ws_mall_after_sale WHERE AFTER_SALE_NO = #{no}")
    WsMallAfterSale selectByNoIncludingDeleted(@Param("no") String no);

    @Select("SELECT * FROM ws_mall_after_sale WHERE USER_ID = #{userId} AND REQUEST_ID = #{requestId}")
    WsMallAfterSale selectByUserRequestIncludingDeleted(@Param("userId") Long userId,
                                                        @Param("requestId") String requestId);

    /** 状态推进：精确前态 + 版本号双条件。 */
    @Update("""
            UPDATE ws_mall_after_sale
               SET AFTER_SALE_STATUS = #{toStatus}, VERSION = VERSION + 1,
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0
               AND AFTER_SALE_STATUS = #{fromStatus} AND VERSION = #{version}""")
    int casStatus(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                  @Param("toStatus") int toStatus, @Param("version") Integer version,
                  @Param("operator") Long operator, @Param("now") String now);

    /** 审核通过/驳回：状态与审核痕迹一次写完，避免出现「已通过但没有审核人」的中间态。 */
    @Update("""
            UPDATE ws_mall_after_sale
               SET AFTER_SALE_STATUS = #{toStatus}, VERSION = VERSION + 1,
                   AUDIT_BY = #{operator}, AUDIT_TIME = #{now}, AUDIT_REMARK = #{remark},
                   REJECT_REASON = #{rejectReason}, FINISH_TIME = #{finishTime},
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0
               AND AFTER_SALE_STATUS = #{fromStatus} AND VERSION = #{version}""")
    int casAudit(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                 @Param("toStatus") int toStatus, @Param("version") Integer version,
                 @Param("remark") String remark, @Param("rejectReason") String rejectReason,
                 @Param("finishTime") String finishTime,
                 @Param("operator") Long operator, @Param("now") String now);

    /** 仓库确认收到退货。 */
    @Update("""
            UPDATE ws_mall_after_sale
               SET AFTER_SALE_STATUS = #{toStatus}, VERSION = VERSION + 1,
                   RECEIVE_BY = #{operator}, RECEIVE_TIME = #{now},
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0
               AND AFTER_SALE_STATUS = #{fromStatus} AND VERSION = #{version}""")
    int casReceive(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                   @Param("toStatus") int toStatus, @Param("version") Integer version,
                   @Param("operator") Long operator, @Param("now") String now);

    /** 质检落定：结论、说明与状态同语句，杜绝「已质检但没有结论」。 */
    @Update("""
            UPDATE ws_mall_after_sale
               SET AFTER_SALE_STATUS = #{toStatus}, VERSION = VERSION + 1,
                   INSPECT_BY = #{operator}, INSPECT_TIME = #{now},
                   INSPECT_RESULT = #{result}, INSPECT_REMARK = #{remark},
                   REJECT_REASON = #{rejectReason}, FINISH_TIME = #{finishTime},
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0
               AND AFTER_SALE_STATUS = #{fromStatus} AND VERSION = #{version}""")
    int casInspect(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                   @Param("toStatus") int toStatus, @Param("version") Integer version,
                   @Param("result") Integer result, @Param("remark") String remark,
                   @Param("rejectReason") String rejectReason,
                   @Param("finishTime") String finishTime,
                   @Param("operator") Long operator, @Param("now") String now);

    /** 完成：落完成时间。 */
    @Update("""
            UPDATE ws_mall_after_sale
               SET AFTER_SALE_STATUS = #{toStatus}, VERSION = VERSION + 1,
                   FINISH_TIME = #{now}, UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0
               AND AFTER_SALE_STATUS = #{fromStatus} AND VERSION = #{version}""")
    int casFinish(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                  @Param("toStatus") int toStatus, @Param("version") Integer version,
                  @Param("operator") Long operator, @Param("now") String now);

    /**
     * 某订单明细上「处理中 + 已成功」的累计售后数量。排除 7已驳回/9已取消（无实际退货，算进去用户永远退不满）；
     * 8待人工计入——证据待查期间必须占住额度。
     */
    @Select("""
            SELECT COALESCE(SUM(i.QUANTITY), 0) FROM ws_mall_after_sale_item i
              JOIN ws_mall_after_sale a ON a.ID = i.AFTER_SALE_ID
             WHERE i.DATA_STATUS = 0 AND a.DATA_STATUS = 0
               AND i.ORDER_ITEM_ID = #{orderItemId}
               AND a.AFTER_SALE_STATUS NOT IN (7, 9)""")
    int sumOccupiedQuantity(@Param("orderItemId") Long orderItemId);

    /** 某订单已成功退款的累计金额（退款单成功态求和）。 */
    @Select("""
            SELECT COALESCE(SUM(r.REFUND_AMOUNT_FEN), 0) FROM ws_mall_refund r
             WHERE r.DATA_STATUS = 0 AND r.ORDER_ID = #{orderId} AND r.REFUND_STATUS = 2""")
    long sumSucceededRefundFen(@Param("orderId") Long orderId);

    /** 本人售后列表。 */
    @Select("""
            SELECT * FROM ws_mall_after_sale
             WHERE DATA_STATUS = 0 AND USER_ID = #{userId}
             ORDER BY ID DESC""")
    List<WsMallAfterSale> selectByUser(@Param("userId") Long userId);

    /** PC 台账：只返回操作员所归属前置仓的售后单。范围过滤必须在 SQL 里，查完再筛会让分页边界按全量算、越权行漏出。 */
    @Select("""
            <script>
            SELECT a.* FROM ws_mall_after_sale a
             WHERE a.DATA_STATUS = 0
               AND EXISTS (SELECT 1 FROM ws_mall_warehouse_operator o
                            WHERE o.DATA_STATUS = 0 AND o.OPERATOR_ID = #{operatorId}
                              AND o.WAREHOUSE_ID = a.WAREHOUSE_ID)
               <if test="afterSaleNo != null and afterSaleNo != ''">
                 AND a.AFTER_SALE_NO = #{afterSaleNo}
               </if>
               <if test="orderNo != null and orderNo != ''">
                 AND a.ORDER_NO = #{orderNo}
               </if>
               <if test="afterSaleStatus != null">
                 AND a.AFTER_SALE_STATUS = #{afterSaleStatus}
               </if>
               <if test="afterSaleType != null">
                 AND a.AFTER_SALE_TYPE = #{afterSaleType}
               </if>
             ORDER BY a.ID DESC
            </script>""")
    com.baomidou.mybatisplus.core.metadata.IPage<WsMallAfterSale> selectPageForOperator(
            com.baomidou.mybatisplus.core.metadata.IPage<WsMallAfterSale> page,
            @Param("operatorId") Long operatorId,
            @Param("afterSaleNo") String afterSaleNo,
            @Param("orderNo") String orderNo,
            @Param("afterSaleStatus") Integer afterSaleStatus,
            @Param("afterSaleType") Integer afterSaleType);
}
