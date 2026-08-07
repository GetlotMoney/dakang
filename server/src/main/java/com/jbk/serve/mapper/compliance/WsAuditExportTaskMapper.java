package com.jbk.serve.mapper.compliance;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.compliance.po.WsAuditExportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 审计导出任务 Mapper（B23）。状态迁移统一走 Service 的前态 CAS lambdaUpdate，
 * 不在此写 SQL，保证"什么状态能转到什么状态"只有一份实现。
 *
 * @author dakang
 * @since 2026-08-06
 */
@Mapper
public interface WsAuditExportTaskMapper extends BaseMapper<WsAuditExportTask> {

    /**
     * 取当天已用的<b>最大</b>任务号序号；无记录返回 0。
     *
     * <p>手写 SQL 而非 Wrapper，是为了同时越过两个坑：</p>
     * <ol>
     *   <li><b>逻辑删除</b>：PO 继承 {@code BaseEntity}，{@code DATA_STATUS} 带
     *       {@code @TableLogic}，MyBatis-Plus 会往它<i>自己生成</i>的每条查询里注入
     *       {@code AND DATA_STATUS = 0}。这个注入与调用方给的条件是 AND 关系，
     *       写 {@code .and(w -> eq(0).or().eq(1))} 也<b>绕不开</b>——结果仍等价于只看未删行。
     *       而唯一键 {@code uk_audit_export_task_no} 不区分逻辑删除：当天只要有一行被逻辑删除，
     *       算出的序号就会落在一个物理上仍被占用的值上，此后当天每次申请都撞键，
     *       重试三次也全是同一个号，属确定性故障。手写 {@code @Select} 不经过该注入器，
     *       口径与唯一键一致。</li>
     *   <li><b>字符串序</b>：先前按 {@code ORDER BY TASK_NO DESC} 取最大值。序号定宽 3 位时字典序
     *       等于数值序，但一旦越过 999 就断裂——{@code '...-999' > '...-1000'}，
     *       于是永远算出 1000 并永久撞键。{@code CAST(... AS UNSIGNED)} 按数值比较，与位宽无关。</li>
     * </ol>
     *
     * <p>非数字后缀 CAST 后为 0，不会污染最大值。</p>
     *
     * @param prefix 形如 {@code AUD-EXP-20260806-}
     */
    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING(TASK_NO, CHAR_LENGTH(#{prefix}) + 1) AS UNSIGNED)), 0)"
            + " FROM ws_audit_export_task WHERE TASK_NO LIKE CONCAT(#{prefix}, '%')")
    long maxTaskSeqOfDay(@Param("prefix") String prefix);
}
