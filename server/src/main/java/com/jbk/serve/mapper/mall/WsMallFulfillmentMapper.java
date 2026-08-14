package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallFulfillment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商城履约任务 Mapper（E2E-09 S3）。
 *
 * <p>所有状态推进都是「精确前态 + 版本号」双条件 CAS，返回影响行数由调用方判读。
 * 只查一遍再更新会让两个并发操作各自看到同一个前态、各推进一次——重复轨迹、
 * 重复消息、重复审计都由此而来。</p>
 *
 * @author dakang
 * @since 2026-08-09
 */
@Mapper
public interface WsMallFulfillmentMapper extends BaseMapper<WsMallFulfillment> {

    /** 按订单号原样读（绕过逻辑删除过滤）：让"任务被删了"成为可判定事实而不是"查不到"。 */
    @Select("SELECT * FROM ws_mall_fulfillment WHERE ORDER_NO = #{orderNo}")
    WsMallFulfillment selectByOrderNoIncludingDeleted(@Param("orderNo") String orderNo);

    /** 按订单 ID 原样读：并发建任务撞唯一键后由此重读赢家。 */
    @Select("SELECT * FROM ws_mall_fulfillment WHERE ORDER_ID = #{orderId}")
    WsMallFulfillment selectByOrderIdIncludingDeleted(@Param("orderId") Long orderId);

    /**
     * 状态推进：精确前态 + 版本号双条件。
     *
     * <p>时间列名由调用方给定（PICK_TIME/PACK_TIME/…），故用字符串拼接——该值只来自
     * 服务内部常量，绝不接受外部输入。</p>
     */
    @Update("""
            <script>
            UPDATE ws_mall_fulfillment
               SET FULFILL_STATUS = #{toStatus}, VERSION = VERSION + 1,
                   <if test="timeColumn != null">${timeColumn} = #{now},</if>
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0
               AND FULFILL_STATUS = #{fromStatus} AND VERSION = #{version}
            </script>""")
    int casStatus(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                  @Param("toStatus") int toStatus, @Param("version") Integer version,
                  @Param("timeColumn") String timeColumn,
                  @Param("operator") Long operator, @Param("now") String now);

    /**
     * 分配配送员：以 COURIER_ID IS NULL 为前置，两个操作员同时分配只有一个能中。
     * 分配与状态推进必须同一条语句完成，中间态会被另一次分配覆盖。
     */
    @Update("""
            UPDATE ws_mall_fulfillment
               SET COURIER_ID = #{courierId}, FULFILL_STATUS = #{toStatus},
                   VERSION = VERSION + 1, ASSIGN_TIME = #{now},
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0 AND COURIER_ID IS NULL
               AND FULFILL_STATUS = #{fromStatus} AND VERSION = #{version}""")
    int casAssign(@Param("id") Long id, @Param("courierId") Long courierId,
                  @Param("fromStatus") int fromStatus, @Param("toStatus") int toStatus,
                  @Param("version") Integer version,
                  @Param("operator") Long operator, @Param("now") String now);

    /**
     * 签收落定：状态、签收时间、方式、备注一次写完。
     * 分两条语句会出现"已签收但没有签收时间"的中间态被读到。
     */
    @Update("""
            UPDATE ws_mall_fulfillment
               SET FULFILL_STATUS = #{toStatus}, VERSION = VERSION + 1,
                   SIGN_TIME = #{now}, SIGN_METHOD = #{signMethod}, SIGN_REMARK = #{signRemark},
                   UPDATE_BY = #{operator}, UPDATE_TIME = #{now}
             WHERE ID = #{id} AND DATA_STATUS = 0
               AND FULFILL_STATUS = #{fromStatus} AND VERSION = #{version}""")
    int casSign(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                @Param("toStatus") int toStatus, @Param("version") Integer version,
                @Param("signMethod") Integer signMethod, @Param("signRemark") String signRemark,
                @Param("operator") Long operator, @Param("now") String now);

    /** 已支付但尚无履约任务的订单号：任务生成 Worker 的扫描面。 */
    @Select("""
            SELECT o.ORDER_NO FROM ws_mall_order o
             WHERE o.DATA_STATUS = 0 AND o.ORDER_STATUS = #{paidStatus}
               AND NOT EXISTS (SELECT 1 FROM ws_mall_fulfillment f WHERE f.ORDER_ID = o.ID)
             ORDER BY o.ID ASC LIMIT #{limit}""")
    List<String> scanPaidOrdersWithoutTask(@Param("paidStatus") int paidStatus,
                                           @Param("limit") int limit);
    /**
     * 渠道冻结：0 未确定 → 1 自营 / 2 第三方，只允许迁移一次。FULFILL_MODE=0 前态只有一方能命中，0 行方必须整事务回滚——
     * 先查再写会让同一包裹同时挂在两条承运链上且事后无法裁决。
     */
    @Update("""
            UPDATE ws_mall_fulfillment
               SET FULFILL_MODE = #{toMode},
                   VERSION = VERSION + 1,
                   UPDATE_BY = #{operator},
                   UPDATE_TIME = #{now}
             WHERE ID = #{id}
               AND DATA_STATUS = 0
               AND FULFILL_MODE = 0
            """)
    int casFreezeMode(@Param("id") Long id, @Param("toMode") int toMode,
                      @Param("operator") Long operator, @Param("now") String now);

}
