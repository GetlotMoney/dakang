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
     * 子指令终态累加。三列互斥：一次调用恰有一列 +1（由调用方按终态映射好传 1/0）。
     *
     * <p>不带 VERSION：累加是可交换操作，两个子指令并发回写互不覆盖（各自 +1），
     * 乐观锁反而会让其中一个白白失败重试。防重复累加靠调用方——指令状态机的
     * {@code conditionalTransit} 保证每条子指令只进入终态一次，钩子随之只触发一次。</p>
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
     * 计数满则置聚合终态（幂等 CAS：前态必须仍是 1处理中）。
     *
     * <p>终态判定放进 WHERE 而不是先查后判：并发的最后两条子指令同时回写时，
     * 两边都可能看到「已满」，靠 {@code BATCH_STATUS = 1} 前态保证只有一个赢家置终态。
     * 聚合口径：全成→2；有成有败→3；全败→4。部分完成(PARTIAL)计入 FAIL_COUNT，
     * 「部分成功」的批次绝不显示为全部成功（任务书 S11）。</p>
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
     * 收敛服务中断留下的陈旧批次。
     *
     * <p>批次可能在展开部分子指令后进程退出。已有子指令仍由正常状态机进入终态，
     * 未展开目标没有指令行可供超时扫描处理。本语句只在“已有子指令全部终态”时重算
     * 三类计数，并把缺失目标计入失败；仍有待下发/已下发/已回执子指令时绝不提前结算。</p>
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
