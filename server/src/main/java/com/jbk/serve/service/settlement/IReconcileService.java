package com.jbk.serve.service.settlement;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.settlement.po.WsReconcileTask;

/**
 * 日对账服务（E2E-08 包C / REQ-023）。
 *
 * <p>口径（任务书二.6 冻结）：日切按流水/事实 CREATE_TIME 的 varchar(14) 日前缀；
 * 五维内部对账（支付事实↔支付单↔订单、完成单↔资金流水、卡账本连续性、
 * 分账合计↔订单基数、收益账本连续性）；差异分类=单边/金额不符/状态不符/账本断裂；
 * 全程按 PAY_SOURCE 隔离 Pay-Sim 口径（外部账单侧留扩展位）。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
public interface IReconcileService extends IService<WsReconcileTask> {

    /**
     * 执行指定账期对账：同账期重跑=旧差异整批替换、任务行原位更新（uk_reconcile_task_date）。
     *
     * @param bizDate 账期日 yyyyMMdd
     * @return 本轮任务行（含核对项数/差异数/终态）
     */
    WsReconcileTask runFor(String bizDate);
}
