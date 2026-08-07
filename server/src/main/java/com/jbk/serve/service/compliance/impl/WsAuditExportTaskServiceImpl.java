package com.jbk.serve.service.compliance.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.compliance.WsAuditExportTaskMapper;
import com.jbk.serve.service.compliance.IWsAuditExportTaskService;
import com.jbk.tool.consts.compliance.ComplianceEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.compliance.bo.WsAuditExportTaskBo;
import com.jbk.tool.data.compliance.po.WsAuditExportTask;
import com.jbk.tool.data.compliance.vo.WsAuditExportTaskVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.MaskUtils;
import com.jbk.tool.utils.OptionalUtils;
import com.jbk.tool.utils.satoken.StpKit;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 审计导出任务服务实现（B23）。
 *
 * <p><b>为什么不实现"生成文件"</b>：导出文件的产出与留存依赖对象存储，尚未接入。
 * 本服务只负责把申请事实落库并维护状态机，任务恒停在「待生成」；
 * 把它推到「已生成」就是在合规页上伪造导出成功，属 PC 职责边界明令禁止（决策 D-003）。
 * 对象存储接入后，由真实文件产出驱动 RUNNING→DONE 并回填摘要与过期时间。</p>
 *
 * @author dakang
 * @since 2026-08-06
 */
@Service
public class WsAuditExportTaskServiceImpl
        extends ServiceImpl<WsAuditExportTaskMapper, WsAuditExportTask>
        implements IWsAuditExportTaskService {

    /**
     * 一期固定脱敏口径，随申请时点写入快照。
     * 写死在服务端而非由前端传入：脱敏规则若可被调用方指定，导出范围就等于不受控。
     */
    private static final String MASKING_RULE = "手机号中间四位脱敏；身份信息不导出";

    /** 允许申请的导出范围白名单；范围值参与文件生成语义，不接受自由文本。 */
    private static final List<String> ALLOWED_SCOPES = List.of(
            "操作日志", "登录日志", "订单追溯", "设备事件", "指令回执", "领域事件");

    private static final int MAX_SCOPE_COUNT = ALLOWED_SCOPES.size();

    /** 任务号撞唯一键后的重取次数上限；超过即认为存在异常争用，明确报错而非无限重试。 */
    private static final int TASK_NO_MAX_ATTEMPTS = 3;

    @Override
    public PageDataVo<WsAuditExportTaskVo> pageList(WsAuditExportTaskBo bo) {
        Page<WsAuditExportTask> page = page(
                new Page<>(bo.getCurrent(), bo.getSize()),
                Wrappers.lambdaQuery(WsAuditExportTask.class)
                        .eq(ObjectUtil.isNotNull(bo.getTaskStatus()),
                                WsAuditExportTask::getTaskStatus, bo.getTaskStatus())
                        // 申请时间同秒时用 ID 兜底，保证分页稳定不漏不重
                        .orderByDesc(WsAuditExportTask::getCreateTime)
                        .orderByDesc(WsAuditExportTask::getId)
        );
        List<WsAuditExportTaskVo> list = page.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());
        return PageDataVo.getPageData(list, page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsAuditExportTaskVo apply(WsAuditExportTaskBo bo) {
        List<String> scopes = bo.getExportScope().stream()
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
        if (scopes.isEmpty()) {
            throw new JbkException("导出范围不为空");
        }
        if (scopes.size() > MAX_SCOPE_COUNT) {
            throw new JbkException("导出范围超出可选项数量");
        }
        List<String> illegal = scopes.stream()
                .filter(scope -> !ALLOWED_SCOPES.contains(scope))
                .collect(Collectors.toList());
        if (!illegal.isEmpty()) {
            throw new JbkException("不支持的导出范围：" + String.join("、", illegal));
        }
        checkTimeRange(bo.getStartTime(), bo.getEndTime());

        long operatorId = StpKit.MANAGE.getLoginIdAsLong();
        String operatorName = StrUtil.blankToDefault(
                (String) StpKit.MANAGE.getExtra(StpKit.EXTRA_NAME), "未知操作员");

        WsAuditExportTask task = new WsAuditExportTask()
                .setExportScope(String.join("、", scopes))
                .setFilterSummary(buildFilterSummary(bo))
                .setApplyReason(bo.getApplyReason().trim())
                .setMaskingRule(MASKING_RULE)
                // 恒为待生成：本服务不产出文件，也不允许调用方指定初始状态
                .setTaskStatus(ComplianceEnum.AuditExportStatus.PENDING.getValue())
                .setApplyByName(operatorName);
        task.setCreateBy(operatorId);
        task.setUpdateBy(operatorId);

        // 任务号按天递增，并发下两个申请可能算出同一序号，由唯一键拒绝后者。
        // 撞键不该以「服务端发生异常」抛给用户——重取序号重试即可，只在连续失败后才放弃
        for (int attempt = 1; ; attempt++) {
            task.setTaskNo(nextTaskNo());
            try {
                save(task);
                break;
            } catch (DuplicateKeyException e) {
                task.setId(null);
                if (attempt >= TASK_NO_MAX_ATTEMPTS) {
                    throw new JbkException("任务号生成冲突，请重试");
                }
            }
        }
        return toVo(task);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsAuditExportTaskVo retry(Long id) {
        WsAuditExportTask task = getById(id);
        OptionalUtils.nullToElseThrow(task, "导出任务不存在");

        long operatorId = StpKit.MANAGE.getLoginIdAsLong();
        // 前态 CAS：并发重试或状态已被推进时影响 0 行，按状态冲突拒绝，
        // 不做"读出判断再写回"——那样两个并发重试都会通过
        boolean changed = update(Wrappers.lambdaUpdate(WsAuditExportTask.class)
                .eq(WsAuditExportTask::getId, id)
                .eq(WsAuditExportTask::getTaskStatus, ComplianceEnum.AuditExportStatus.FAILED.getValue())
                .set(WsAuditExportTask::getTaskStatus, ComplianceEnum.AuditExportStatus.PENDING.getValue())
                .set(WsAuditExportTask::getFailureReason, null)
                .set(WsAuditExportTask::getUpdateBy, operatorId)
                .set(WsAuditExportTask::getUpdateTime, DateUtils.time())
        );
        if (!changed) {
            throw new JbkException("仅失败的任务可以重试");
        }
        return toVo(getById(id));
    }

    // ==================== 内部 ====================

    /**
     * 任务号 AUD-EXP-yyyyMMdd-NNN，序号取当天<b>最大已用序号 +1</b>。
     *
     * <p>选号口径必须与唯一键 {@code uk_audit_export_task_no} 完全一致——含逻辑删除行、
     * 按数值而非字符串取最大。这两点都做不到用 Wrapper 表达，故落在
     * {@link WsAuditExportTaskMapper#maxTaskSeqOfDay} 的手写 SQL 里，原因见该方法注释。</p>
     *
     * <p>序号超过 999 时 {@code %03d} 自然扩宽为 4 位（TASK_NO 为 varchar(40)，
     * 前缀占 17 字符，余量充足）；扩宽不影响取号，因为比较已改为数值比较。</p>
     *
     * <p>并发下两个申请仍可能取到同一序号，由唯一键拒绝后者，调用方重取重试
     * （申请是低频人工动作，不值得为此引入号段表）。</p>
     */
    // 包级可见而非 private：AuditExportDbTest 需要在真 MySQL 上直接驱动它。
    // 这两个失效模式（逻辑删除行占号、序号越过 999）在纯单测里都复现不出来。
    String nextTaskNo() {
        String day = DateUtils.time().substring(0, 8);
        String prefix = "AUD-EXP-" + day + "-";
        long nextSeq = getBaseMapper().maxTaskSeqOfDay(prefix) + 1;
        return prefix + String.format("%03d", nextSeq);
    }

    private void checkTimeRange(String startTime, String endTime) {
        String start = startTime.trim();
        String end = endTime.trim();
        if (!start.matches("\\d{14}") || !end.matches("\\d{14}")) {
            throw new JbkException("时间格式必须为 yyyyMMddHHmmss");
        }
        if (start.compareTo(end) >= 0) {
            throw new JbkException("起始时间必须早于截止时间");
        }
    }

    /**
     * 筛选条件在申请时冻结成文本快照：事后改了查询口径也不影响这条申请记录的可追溯性。
     *
     * <p>关键字按 {@link #MASKING_RULE} 同一口径脱敏后再落库。这两个字段的前端占位文案
     * 就在引导填手机号与姓名，若原文入库，这张<b>申请单本身会比它承诺要脱敏的那份导出文件
     * 更不脱敏</b>，且对每个持导出权限的人明文可见——审计登记簿反倒成了新的泄露面。</p>
     */
    private String buildFilterSummary(WsAuditExportTaskBo bo) {
        StringBuilder sb = new StringBuilder()
                .append(bo.getStartTime().trim()).append(" 至 ").append(bo.getEndTime().trim());
        if (StrUtil.isNotBlank(bo.getOperatorKeyword())) {
            sb.append("；操作人 ").append(MaskUtils.maskPhoneLike(bo.getOperatorKeyword().trim()));
        }
        if (StrUtil.isNotBlank(bo.getBusinessKeyword())) {
            sb.append("；业务对象 ").append(MaskUtils.maskPhoneLike(bo.getBusinessKeyword().trim()));
        }
        return sb.toString();
    }

    private WsAuditExportTaskVo toVo(WsAuditExportTask task) {
        return new WsAuditExportTaskVo()
                .setId(task.getId())
                .setTaskNo(task.getTaskNo())
                .setExportScope(task.getExportScope())
                .setFilterSummary(task.getFilterSummary())
                .setApplyReason(task.getApplyReason())
                .setMaskingRule(task.getMaskingRule())
                .setTaskStatus(task.getTaskStatus())
                .setTaskStatusName(ComplianceEnum.AuditExportStatus.getType(task.getTaskStatus()).getDesc())
                .setApplyByName(task.getApplyByName())
                .setCreateTime(task.getCreateTime())
                .setFailureReason(task.getFailureReason())
                .setFileDigest(task.getFileDigest())
                .setExpireTime(task.getExpireTime());
    }
}
