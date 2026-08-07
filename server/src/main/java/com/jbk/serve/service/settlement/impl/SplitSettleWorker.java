package com.jbk.serve.service.settlement.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.service.settlement.ISplitService;
import com.jbk.tool.consts.settlement.SettlementEnum;
import com.jbk.tool.data.settlement.po.WsSplitRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分账推进 Worker（E2E-08 包B）：把「待分账」推进为「已分账」并触发收益入账。
 * Pay-Sim 环境为账务计提语义（记账不动钱）；真实微信分账接入后推进段替换为
 * 分账 API 调用 + 回执驱动，行状态机不变。
 *
 * <p>节奏对齐既有 Fact Worker 先例（fixedDelay=30s）。逐行推进走
 * {@link ISplitService#settleOne}（接口经代理调用才有事务；Worker 内部私有方法
 * 自调用会绕过事务代理——这里刻意不放业务逻辑）。单行失败不阻塞同批其它行。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
@Slf4j
@Component
public class SplitSettleWorker {

    private static final int BATCH = 50;

    @Autowired
    private ISplitService splitService;

    @Scheduled(fixedDelay = 30_000)
    public void settlePending() {
        // D-421 冻结期：只扫创建已满冻结期的待分账行（settleOne 内还有同口径守卫兜底）
        List<WsSplitRecord> pending = splitService.list(Wrappers.lambdaQuery(WsSplitRecord.class)
                .eq(WsSplitRecord::getSplitStatus, SettlementEnum.SplitStatus.PENDING.getValue())
                .le(WsSplitRecord::getCreateTime, splitService.settleableCreateTimeThreshold())
                .orderByAsc(WsSplitRecord::getId)
                .last("LIMIT " + BATCH));
        for (WsSplitRecord row : pending) {
            try {
                // 只传 ID：settleOne 事务内锁定读当前行，扫描结果仅当候选集（D-421 R1）
                splitService.settleOne(row.getId());
            }
            catch (Exception e) {
                // 单行失败只记日志，下轮重试（行仍处待分账）；不置 3 失败态——
                // 失败态留给真实分账 API 的明确拒绝回执，内部计提无「业务性失败」语义
                log.error("分账推进失败，待下轮重试：split={} {}", row.getId(), e.getMessage());
            }
        }
    }
}
