package com.jbk.serve.service.compliance;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.compliance.bo.WsAuditExportTaskBo;
import com.jbk.tool.data.compliance.po.WsAuditExportTask;
import com.jbk.tool.data.compliance.vo.WsAuditExportTaskVo;

/**
 * 审计导出任务服务（B23 / REQ-024、REQ-066）。
 *
 * @author dakang
 * @since 2026-08-06
 */
public interface IWsAuditExportTaskService extends IService<WsAuditExportTask> {

    /** 分页查询任务，按申请时间倒序。 */
    PageDataVo<WsAuditExportTaskVo> pageList(WsAuditExportTaskBo bo);

    /**
     * 提交导出申请：服务端组装筛选快照与脱敏规则并落库，任务停在「待生成」。
     *
     * @return 新建任务
     */
    WsAuditExportTaskVo apply(WsAuditExportTaskBo bo);

    /**
     * 重试失败任务：仅「失败」可回到「待生成」，其余状态一律拒绝。
     *
     * @return 重试后的任务
     */
    WsAuditExportTaskVo retry(Long id);
}
