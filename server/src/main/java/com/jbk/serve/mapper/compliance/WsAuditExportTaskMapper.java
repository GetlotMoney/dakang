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
     * 取当天已用的最大任务号序号；无记录返回 0。手写 SQL 避开两个坑：
     * ① @TableLogic 会给 Wrapper 查询强注 DATA_STATUS=0，而 uk_audit_export_task_no 不区分逻辑删除——被删行占号会导致当天永久撞键；
     * ② 字符串序在序号越过 999 后断裂（'...-999' > '...-1000'），CAST AS UNSIGNED 按数值比较；非数字后缀 CAST 为 0 不污染最大值。
     *
     * @param prefix 形如 {@code AUD-EXP-20260806-}
     */
    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING(TASK_NO, CHAR_LENGTH(#{prefix}) + 1) AS UNSIGNED)), 0)"
            + " FROM ws_audit_export_task WHERE TASK_NO LIKE CONCAT(#{prefix}, '%')")
    long maxTaskSeqOfDay(@Param("prefix") String prefix);
}
