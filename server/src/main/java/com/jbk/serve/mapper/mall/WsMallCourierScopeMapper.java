package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallCourierScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商城配送员前置仓服务范围 Mapper（E2E-09 S3）。
 *
 * @author dakang
 * @since 2026-08-09
 */
@Mapper
public interface WsMallCourierScopeMapper extends BaseMapper<WsMallCourierScope> {

    /**
     * 该配送员是否覆盖该前置仓（且绑定未解除）。
     * 空结果即"范围外"——默认拒绝，不做任何文本层面的推断。
     */
    @Select("""
            SELECT COUNT(*) FROM ws_mall_courier_scope
             WHERE DATA_STATUS = 0 AND WAREHOUSE_ID = #{warehouseId} AND COURIER_ID = #{courierId}""")
    int countActiveScope(@Param("warehouseId") Long warehouseId,
                         @Param("courierId") Long courierId);

    /**
     * 某前置仓下可分配的配送员候选：绑定生效 + 准入启用 + 未删除。
     * 三个条件缺一不可，且必须在库层同时成立——分开查再在内存里过滤会漏掉并发停用。
     */
    @Select("""
            SELECT c.ID FROM ws_mall_courier_scope s
              JOIN ws_courier c ON c.ID = s.COURIER_ID
             WHERE s.DATA_STATUS = 0 AND s.WAREHOUSE_ID = #{warehouseId}
               AND c.DATA_STATUS = 0 AND c.COURIER_STATUS = #{enabledStatus}
             ORDER BY c.ID ASC""")
    List<Long> selectAssignableCourierIds(@Param("warehouseId") Long warehouseId,
                                          @Param("enabledStatus") int enabledStatus);
}
