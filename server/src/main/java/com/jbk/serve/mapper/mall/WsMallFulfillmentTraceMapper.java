package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallFulfillmentTrace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商城履约轨迹 Mapper（E2E-09 S3）。
 *
 * @author dakang
 * @since 2026-08-09
 */
@Mapper
public interface WsMallFulfillmentTraceMapper extends BaseMapper<WsMallFulfillmentTrace> {

    /** 按幂等键原样读：撞键即已写过，不再重复落轨迹。 */
    @Select("SELECT * FROM ws_mall_fulfillment_trace WHERE BIZ_IDEMPOTENCY_KEY = #{key}")
    WsMallFulfillmentTrace selectByKeyIncludingDeleted(@Param("key") String key);

    /**
     * 某节点的有效轨迹（可能不止一条：唯一键只钉住幂等键，绕开键手工插入仍可造出第二条）。
     * 配送归属校验因此要求「恰一条」，多于一条即证据不可信。
     */
    @Select("""
            SELECT * FROM ws_mall_fulfillment_trace
             WHERE DATA_STATUS = 0 AND ORDER_NO = #{orderNo} AND TRACE_NODE = #{node}""")
    List<WsMallFulfillmentTrace> selectLiveByNode(@Param("orderNo") String orderNo,
                                                  @Param("node") int node);

    /** 时间线：按节点时间与 ID 升序，三端同源同序。 */
    @Select("""
            SELECT * FROM ws_mall_fulfillment_trace
             WHERE DATA_STATUS = 0 AND ORDER_NO = #{orderNo}
             ORDER BY TRACE_TIME ASC, ID ASC""")
    List<WsMallFulfillmentTrace> selectTimeline(@Param("orderNo") String orderNo);
}
