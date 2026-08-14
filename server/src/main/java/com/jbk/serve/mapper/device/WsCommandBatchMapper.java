package com.jbk.serve.mapper.device;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.device.po.WsCommandBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 批量指令任务 Mapper（E2E-05）。SQL 内不含 SQL 注释（Druid WallFilter）。
 *
 * <p>聚合数量的回写路径只有 {@link #accumulateChildTerminal} 一条：子指令终态各自
 * +1 到对应计数列，靠单条 UPDATE 的原子性并发安全——绝不读出-加-写回。
 * 聚合终态由 {@link #settleIfComplete} 在计数满时置入，两步都幂等可重放。</p>
 */
@Mapper
public interface WsCommandBatchMapper extends BaseMapper<WsCommandBatch> {

    /**
     * 子指令终态累加：一次调用恰有一列 +1。不带 VERSION（累加可交换，乐观锁只会白白失败重试）；
     * 防重复累加靠 {@code conditionalTransit} 保证每条子指令只进终态一次。
     */
    @Update("UPDATE ws_command_batch SET "
            + "SUCCESS_COUNT = SUCCESS_COUNT + #{successDelta}, "
            + "FAIL_COUNT = FAIL_COUNT + #{failDelta}, "
            + "TIMEOUT_COUNT = TIMEOUT_COUNT + #{timeoutDelta}, "
            + "UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND DATA_STATUS = 0")
    int accumulateChildTerminal(@Param("id") Long id,
                                @Param("successDelta") int successDelta,
                                @Param("failDelta") int failDelta,
                                @Param("timeoutDelta") int timeoutDelta,
                                @Param("now") String now);

    /**
     * 计数满则置聚合终态（幂等 CAS，前态 1处理中 保证并发只有一个赢家）。聚合口径：全成→2；有成有败→3；全败→4；
     * PARTIAL 计入 FAIL_COUNT，「部分成功」绝不显示为全部成功（任务书 S11）。
     */
    @Update("UPDATE ws_command_batch SET "
            + "BATCH_STATUS = CASE "
            + "WHEN SUCCESS_COUNT = TOTAL_COUNT THEN 2 "
            + "WHEN SUCCESS_COUNT = 0 THEN 4 "
            + "ELSE 3 END, "
            + "FINISH_TIME = #{now}, VERSION = VERSION + 1, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND DATA_STATUS = 0 AND BATCH_STATUS = 1 "
            + "AND SUCCESS_COUNT + FAIL_COUNT + TIMEOUT_COUNT >= TOTAL_COUNT")
    int settleIfComplete(@Param("id") Long id, @Param("now") String now);

    /**
     * 收敛服务中断留下的陈旧批次（展开一半进程退出，未展开目标无指令行可扫）。只在已有子指令全部终态时重算计数并把缺失目标计入失败；
     * 仍有活跃子指令时绝不提前结算。
     */
    @Update("UPDATE ws_command_batch b "
            + "LEFT JOIN ("
            + "  SELECT BATCH_ID, COUNT(*) child_count, "
            + "    SUM(CASE WHEN CMD_STATUS = 4 THEN 1 ELSE 0 END) success_count, "
            + "    SUM(CASE WHEN CMD_STATUS IN (5, 7) THEN 1 ELSE 0 END) fail_count, "
            + "    SUM(CASE WHEN CMD_STATUS = 6 THEN 1 ELSE 0 END) timeout_count, "
            + "    SUM(CASE WHEN CMD_STATUS IN (1, 2, 3) THEN 1 ELSE 0 END) active_count "
            + "  FROM ws_command WHERE DATA_STATUS = 0 GROUP BY BATCH_ID"
            + ") x ON x.BATCH_ID = b.ID "
            + "SET b.SUCCESS_COUNT = COALESCE(x.success_count, 0), "
            + "    b.FAIL_COUNT = COALESCE(x.fail_count, 0) "
            + "      + GREATEST(b.TOTAL_COUNT - COALESCE(x.child_count, 0), 0), "
            + "    b.TIMEOUT_COUNT = COALESCE(x.timeout_count, 0), "
            + "    b.BATCH_STATUS = CASE "
            + "      WHEN COALESCE(x.success_count, 0) = b.TOTAL_COUNT THEN 2 "
            + "      WHEN COALESCE(x.success_count, 0) = 0 THEN 4 ELSE 3 END, "
            + "    b.FINISH_TIME = #{now}, b.VERSION = b.VERSION + 1, b.UPDATE_TIME = #{now} "
            + "WHERE b.ID = #{id} AND b.DATA_STATUS = 0 AND b.BATCH_STATUS = 1 "
            + "  AND COALESCE(x.child_count, 0) <= b.TOTAL_COUNT "
            + "  AND COALESCE(x.active_count, 0) = 0 "
            + "  AND COALESCE(x.success_count, 0) + COALESCE(x.fail_count, 0) "
            + "      + COALESCE(x.timeout_count, 0) = COALESCE(x.child_count, 0)")
    int reconcileInterrupted(@Param("id") Long id, @Param("now") String now);
}
