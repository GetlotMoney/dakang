package com.jbk.serve.mapper.mini;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mini.po.WsIdentityConflict;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 身份冲突台账 Mapper。
 *
 * @author dakang
 * @since 2026-08-12
 */
@Mapper
public interface WsIdentityConflictMapper extends BaseMapper<WsIdentityConflict> {

    /**
     * 同一冲突再次发生：原子累加次数并刷新最近时间（读-改-写并发会丢计数）。刻意不碰 HANDLE_STATUS：已处理的冲突复发时状态不回退，由 LAST_OCCUR_TIME 体现。
     *
     * @return 影响行数；0 表示该键当时还不存在（调用方转插入）
     */
    @Update("""
            UPDATE ws_identity_conflict
               SET OCCUR_COUNT = OCCUR_COUNT + 1,
                   LAST_OCCUR_TIME = #{occurTime},
                   UPDATE_TIME = #{occurTime}
             WHERE CONFLICT_KEY = #{conflictKey}
            """)
    int bumpOccurrence(@Param("conflictKey") String conflictKey,
                       @Param("occurTime") String occurTime);
}
