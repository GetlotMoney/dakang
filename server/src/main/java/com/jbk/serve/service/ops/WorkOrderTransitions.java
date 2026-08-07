package com.jbk.serve.service.ops;

import com.jbk.tool.consts.ops.OpsEnum.WorkOrderStatus;
import com.jbk.tool.exception.JbkException;

import java.util.Map;
import java.util.Set;

/**
 * 工单状态机——<b>单一出处</b>（E2E-05 包C，任务书 3.1 冻结口径）。
 *
 * <p>告警转单、机主申报、PC 巡检三来源共用本迁移表；禁止任何 Service 私自
 * 定义第二份合法迁移。迁移表之外的组合一律拒绝（fail-closed），包括把
 * 已关闭/已驳回当作可复活状态。</p>
 *
 * <pre>
 * 机主申报入口：待确认(1)
 * 告警转单/PC巡检入口：待分配(2)
 * 待确认 → 待分配（确认） / 已驳回（驳回，必填原因）
 * 待分配 → 处理中（分配，必须绑定有效员工）
 * 处理中 → 待复核（提交处理结果与证据）
 * 待复核 → 已关闭（复核通过） / 处理中（复核退回，必填意见）
 * </pre>
 *
 * @author dakang
 * @since 2026-07-30
 */
public final class WorkOrderTransitions {

    /** 动作 → (合法前态, 目标态)。目标态唯一：同一动作绝不因入参产生不同去向。 */
    public enum Action {
        CONFIRM(WorkOrderStatus.WAIT_CONFIRM, WorkOrderStatus.WAIT_ASSIGN, "确认"),
        REJECT(WorkOrderStatus.WAIT_CONFIRM, WorkOrderStatus.REJECTED, "驳回"),
        ASSIGN(WorkOrderStatus.WAIT_ASSIGN, WorkOrderStatus.PROCESSING, "分配"),
        SUBMIT_RESULT(WorkOrderStatus.PROCESSING, WorkOrderStatus.WAIT_REVIEW, "提交处理结果"),
        REVIEW_PASS(WorkOrderStatus.WAIT_REVIEW, WorkOrderStatus.CLOSED, "复核通过"),
        REVIEW_RETURN(WorkOrderStatus.WAIT_REVIEW, WorkOrderStatus.PROCESSING, "复核退回");

        private final WorkOrderStatus from;
        private final WorkOrderStatus to;
        private final String desc;

        Action(WorkOrderStatus from, WorkOrderStatus to, String desc) {
            this.from = from;
            this.to = to;
            this.desc = desc;
        }

        public WorkOrderStatus from() {
            return from;
        }

        public WorkOrderStatus to() {
            return to;
        }

        public String desc() {
            return desc;
        }
    }

    /** 终态集合：进入后任何动作都不再受理（自动恢复也不得关单——关闭只有复核一条路）。 */
    private static final Set<WorkOrderStatus> TERMINAL =
            Set.of(WorkOrderStatus.CLOSED, WorkOrderStatus.REJECTED);

    /** 来源 → 入口状态：机主申报须确认，告警转单与后台巡检直接进待分配（任务书 3.1）。 */
    private static final Map<Integer, WorkOrderStatus> ENTRY = Map.of(
            1, WorkOrderStatus.WAIT_ASSIGN,
            2, WorkOrderStatus.WAIT_CONFIRM,
            3, WorkOrderStatus.WAIT_ASSIGN);

    private WorkOrderTransitions() {
    }

    /** 按来源取入口状态；未知来源拒绝。 */
    public static WorkOrderStatus entryStatus(int sourceType) {
        WorkOrderStatus entry = ENTRY.get(sourceType);
        if (entry == null) {
            throw new JbkException("未知工单来源，已拒绝");
        }
        return entry;
    }

    /**
     * 校验动作对当前状态是否合法，返回目标状态。
     * 非法组合抛出并说明当前态——给并发输方一个可读的拒绝理由，而不是含糊的失败。
     */
    public static WorkOrderStatus target(Action action, int currentStatus) {
        WorkOrderStatus current = WorkOrderStatus.getType(currentStatus);
        if (TERMINAL.contains(current)) {
            throw new JbkException("工单已" + current.getDesc() + "，不再受理" + action.desc());
        }
        if (action.from().getValue() != currentStatus) {
            throw new JbkException("工单当前为「" + current.getDesc() + "」，不能执行" + action.desc()
                    + "（需处于「" + action.from().getDesc() + "」）");
        }
        return action.to();
    }
}
