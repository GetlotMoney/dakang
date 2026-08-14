package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallShipment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商城出库包裹 Mapper（E2E-09 L1）。
 *
 * <p>与履约总单同口径：状态推进恒「精确前态 + 版本号」双条件 CAS，影响行数由调用方判读。
 * 物流事件会乱序、会重放、会迟到，只查一遍再更新必然出现「已送达」被一条迟到的
 * 「运输中」推回去。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
@Mapper
public interface WsMallShipmentMapper extends BaseMapper<WsMallShipment> {

    /** 按幂等键原样读（绕过逻辑删除过滤）：让「被删了」成为可判定事实而不是「查不到」。 */
    @Select("SELECT * FROM ws_mall_shipment WHERE BIZ_IDEMPOTENCY_KEY = #{key}")
    WsMallShipment selectByKeyIncludingDeleted(@Param("key") String key);

    /** 按履约总单读全部包裹（一期恒一条正向包裹）。 */
    @Select("SELECT * FROM ws_mall_shipment WHERE FULFILL_ID = #{fulfillId} ORDER BY DIRECTION, SHIPMENT_SEQ")
    List<WsMallShipment> selectByFulfill(@Param("fulfillId") Long fulfillId);

    /** 按订单号读全部包裹：三端展示与共键校验共用。 */
    @Select("SELECT * FROM ws_mall_shipment WHERE ORDER_NO = #{orderNo} ORDER BY DIRECTION, SHIPMENT_SEQ")
    List<WsMallShipment> selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 按承运商 + 运单号定位包裹。
     *
     * <p>物流事件只带运单号，落地时必须由它反查归属；查不到即证据错位，
     * 事件留证转人工而不是「就近挂到某个包裹上」。</p>
     */
    @Select("SELECT * FROM ws_mall_shipment WHERE PROVIDER_CODE = #{providerCode} AND WAYBILL_NO = #{waybillNo}")
    WsMallShipment selectByWaybill(@Param("providerCode") String providerCode,
                                   @Param("waybillNo") String waybillNo);

    /**
     * 写入承运方回执（运单号与承运方订单号）并把包裹推进到已受理。
     *
     * <p>前态必须精确是「待发运」：重试重放时第二次影响 0 行，调用方据此判定
     * 「运单已建过」而不是再建一张。</p>
     */
    @Update("""
            UPDATE ws_mall_shipment
               SET PROVIDER_ORDER_NO = #{providerOrderNo},
                   WAYBILL_NO = #{waybillNo},
                   SHIPMENT_STATUS = #{toStatus},
                   VERSION = VERSION + 1,
                   UPDATE_BY = #{operator},
                   UPDATE_TIME = #{now}
             WHERE ID = #{id}
               AND DATA_STATUS = 0
               AND SHIPMENT_STATUS = #{fromStatus}
               AND VERSION = #{version}
            """)
    int casAccept(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                  @Param("toStatus") int toStatus, @Param("version") Integer version,
                  @Param("providerOrderNo") String providerOrderNo,
                  @Param("waybillNo") String waybillNo,
                  @Param("operator") Long operator, @Param("now") String now);

    /**
     * 包裹状态推进（可选写一个时间列）。
     *
     * <p>时间列名只来自服务内部常量，绝不接受外部输入。</p>
     */
    @Update("""
            <script>
            UPDATE ws_mall_shipment
               SET SHIPMENT_STATUS = #{toStatus},
                   VERSION = VERSION + 1,
                   <if test="timeColumn != null"> ${timeColumn} = #{now}, </if>
                   UPDATE_BY = #{operator},
                   UPDATE_TIME = #{now}
             WHERE ID = #{id}
               AND DATA_STATUS = 0
               AND SHIPMENT_STATUS = #{fromStatus}
               AND VERSION = #{version}
            </script>
            """)
    int casStatus(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                  @Param("toStatus") int toStatus, @Param("version") Integer version,
                  @Param("timeColumn") String timeColumn,
                  @Param("operator") Long operator, @Param("now") String now);

    /** 转人工：任何状态都可进，但必须显式留下原因（由调用方写审计）。 */
    @Update("""
            UPDATE ws_mall_shipment
               SET SHIPMENT_STATUS = #{toStatus},
                   VERSION = VERSION + 1,
                   UPDATE_BY = #{operator},
                   UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0 AND SHIPMENT_STATUS <> #{toStatus}
            """)
    int markNeedManual(@Param("id") Long id, @Param("toStatus") int toStatus,
                       @Param("operator") Long operator, @Param("now") String now);
}
