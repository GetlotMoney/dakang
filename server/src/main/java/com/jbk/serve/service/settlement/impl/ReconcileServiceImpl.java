package com.jbk.serve.service.settlement.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.settlement.WsReconcileDiffMapper;
import com.jbk.serve.mapper.settlement.WsReconcileTaskMapper;
import com.jbk.serve.service.settlement.IReconcileService;
import com.jbk.tool.consts.settlement.SettlementEnum;
import com.jbk.tool.data.settlement.po.WsReconcileDiff;
import com.jbk.tool.data.settlement.po.WsReconcileTask;
import com.jbk.tool.exception.JbkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 日对账实现（E2E-08 包C）。五维核对全部走只读 SQL 聚合（对账绝不改业务数据——
 * 差错处置归 PC 工作台的人工授权动作），差异整批替换写台账。
 *
 * <p>核对 SQL 用 JdbcTemplate 而非各域 Mapper：对账是跨域事实比对，按契约要求
 * 必须跨全部 DATA_STATUS 看资金流水（DATA_STATUS&lt;&gt;0 的资金流水本身就是 mismatch，
 * 走 MP 默认逻辑删除过滤会把这类差异藏起来）。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
@Slf4j
@Service
public class ReconcileServiceImpl extends ServiceImpl<WsReconcileTaskMapper, WsReconcileTask> implements IReconcileService {

    private static final int RUNNING = 1;
    private static final int BALANCED = 2;
    private static final int HAS_DIFF = 3;

    @Autowired
    private WsReconcileDiffMapper diffMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsReconcileTask runFor(String bizDate) {
        if (StrUtil.isBlank(bizDate) || !bizDate.matches("^\\d{8}$")) {
            throw new JbkException("账期日必须是 yyyyMMdd");
        }
        WsReconcileTask task = ensureTask(bizDate);
        // 重跑=旧差异整批替换：台账只保留最新一轮结论。刻意绕过 @TableLogic 走物理删除——
        // 差异行是对账工作记录不是业务事实，逻辑删除会让残留行污染台账计数与报表
        jdbc.update("DELETE FROM ws_reconcile_diff WHERE BIZ_DATE = ?", bizDate);

        List<WsReconcileDiff> diffs = new ArrayList<>();
        int checks = 0;
        checks += checkPaymentFact(bizDate, diffs);
        checks += checkOrderFlow(bizDate, diffs);
        checks += checkCardLedger(bizDate, diffs);
        checks += checkSplitSum(bizDate, diffs);
        checks += checkIncomeLedger(bizDate, diffs);

        for (WsReconcileDiff diff : diffs) {
            diff.setTaskId(task.getId()).setBizDate(bizDate);
            diffMapper.insert(diff);
        }
        update(Wrappers.lambdaUpdate(WsReconcileTask.class)
                .eq(WsReconcileTask::getId, task.getId())
                .set(WsReconcileTask::getTaskStatus, diffs.isEmpty() ? BALANCED : HAS_DIFF)
                .set(WsReconcileTask::getCheckTotal, checks)
                .set(WsReconcileTask::getDiffTotal, diffs.size()));
        return getById(task.getId());
    }

    /**
     * 维度1 payment-fact：当日支付单 ↔ 订单共键一致性（按 PAY_SOURCE 隔离口径，
     * 差异行的 BIZ_KEY 带源标记）。支付成功单必须有订单且订单已达已支付族状态、金额相等。
     */
    private int checkPaymentFact(String bizDate, List<WsReconcileDiff> diffs) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.ORDER_ID AS PAYMENT_ORDER_ID, p.ORDER_NO, p.PAY_AMOUNT, p.PAY_STATUS, p.PAY_SOURCE,
                       o.ID AS OID, o.ORDER_AMOUNT, o.ORDER_STATUS
                FROM ws_payment p LEFT JOIN ws_order o ON o.ORDER_NO = p.ORDER_NO
                WHERE p.CREATE_TIME LIKE ?""", bizDate + "%");
        for (Map<String, Object> r : rows) {
            String key = r.get("PAY_SOURCE") + ":" + r.get("ORDER_NO");
            if (ObjectUtil.isNull(r.get("OID"))) {
                diffs.add(diff(SettlementEnum.DiffType.ONE_SIDED, "payment-fact", key, "存在订单", "订单缺失", "支付单无对应订单（单边账）"));
                continue;
            }
            long paymentOrderId = ((Number) r.get("PAYMENT_ORDER_ID")).longValue();
            long orderId = ((Number) r.get("OID")).longValue();
            if (paymentOrderId != orderId) {
                diffs.add(diff(SettlementEnum.DiffType.STATE_MISMATCH, "payment-fact", key,
                        "payment.ORDER_ID=" + orderId, "payment.ORDER_ID=" + paymentOrderId,
                        "支付单与订单 ID 共键错位"));
                continue;
            }
            int payStatus = ((Number) r.get("PAY_STATUS")).intValue();
            int orderStatus = ((Number) r.get("ORDER_STATUS")).intValue();
            if (payStatus == 2) {
                // 已支付族：2已支付 3出水中 4完成 6异常 7退款 8部分退款——1待支付/5取消即状态不符
                if (orderStatus == 1 || orderStatus == 5) {
                    diffs.add(diff(SettlementEnum.DiffType.STATE_MISMATCH, "payment-fact", key, "订单≥已支付", "订单状态=" + orderStatus, null));
                }
                if (!ObjectUtil.equal(r.get("PAY_AMOUNT"), r.get("ORDER_AMOUNT"))) {
                    diffs.add(diff(SettlementEnum.DiffType.AMOUNT_MISMATCH, "payment-fact", key,
                            String.valueOf(r.get("ORDER_AMOUNT")), String.valueOf(r.get("PAY_AMOUNT")), "支付金额≠订单金额"));
                }
            }
        }
        return rows.size() + checkOrderSideOnly(bizDate, diffs);
    }

    /**
     * 维度1 反向半场：已支付族的微信支付订单必须有成功支付单。
     *
     * <p>只从 ws_payment 出发只能抓"支付无订单"；反向的"订单已收款但无支付事实"
     * （货已发、钱未收）是更危险的资损方向，主环境真实数据实点抓获后补齐。
     * 只对 PAY_WAY=1 微信支付成立——余额/水量支付本就不产生支付单，一刀切会全员误报。</p>
     */
    private int checkOrderSideOnly(String bizDate, List<WsReconcileDiff> diffs) {
        List<Map<String, Object>> orders = jdbc.queryForList("""
                SELECT o.ORDER_NO, o.ORDER_STATUS,
                       (SELECT COUNT(*) FROM ws_payment p
                         WHERE p.ORDER_NO = o.ORDER_NO AND p.PAY_STATUS = 2) AS PAID_ROWS
                FROM ws_order o
                WHERE o.CREATE_TIME LIKE ? AND o.PAY_WAY = 1
                  AND o.ORDER_STATUS IN (2, 3, 4, 7, 8)""", bizDate + "%");
        for (Map<String, Object> r : orders) {
            if (((Number) r.get("PAID_ROWS")).longValue() == 0) {
                diffs.add(diff(SettlementEnum.DiffType.ONE_SIDED, "payment-fact",
                        String.valueOf(r.get("ORDER_NO")), "存在成功支付单",
                        "支付单缺失（订单状态=" + r.get("ORDER_STATUS") + "）",
                        "订单已达已支付族但无支付事实（单边账，资损方向）"));
            }
        }
        return orders.size();
    }

    /**
     * 维度2 order-flow：当日完成的资金动作订单必须有恰一条对应幂等键流水且金额一致。
     * 键前缀按订单类型分派——充值 RECHARGE:、取水 CONSUME:、配送 DELIVERY:（三个前缀
     * 分别由 RechargeDetailVerifier / TradeOrderTxServiceImpl / DeliveryConsumeFlow 写入）。
     * 跨全部 DATA_STATUS：被逻辑删除的资金流水本身是 mismatch（契约条款）。
     *
     * <p>配送单（3类/余额支付/已完成）曾整类漏出核对范围（主环境真实数据实点抓获）：
     * 漏检不产生差异行，反而让批次显示"平账"，比误报更危险。放开类型的同时必须按类型
     * 取对前缀——统一拼 CONSUME: 会把配送单的 DELIVERY: 流水judged成缺失，反向误报。</p>
     */
    private int checkOrderFlow(String bizDate, List<WsReconcileDiff> diffs) {
        List<Map<String, Object>> orders = jdbc.queryForList("""
                SELECT o.ORDER_NO, o.ORDER_TYPE, o.ORDER_STATUS,
                       (SELECT COUNT(*) FROM ws_wallet_flow f
                         WHERE f.BIZ_IDEMPOTENCY_KEY = CONCAT(
                           CASE o.ORDER_TYPE WHEN 2 THEN 'RECHARGE:' WHEN 3 THEN 'DELIVERY:' ELSE 'CONSUME:' END,
                           o.ORDER_NO)) AS FLOW_CNT
                FROM ws_order o
                WHERE o.CREATE_TIME LIKE ?
                  AND ((o.ORDER_TYPE = 2 AND o.ORDER_STATUS >= 2 AND o.ORDER_STATUS <> 5)
                    OR (o.ORDER_TYPE IN (1, 3) AND o.PAY_WAY = 2 AND o.ORDER_STATUS = 4))""", bizDate + "%");
        for (Map<String, Object> r : orders) {
            long cnt = ((Number) r.get("FLOW_CNT")).longValue();
            if (cnt != 1) {
                diffs.add(diff(cnt == 0 ? SettlementEnum.DiffType.ONE_SIDED : SettlementEnum.DiffType.LEDGER_BROKEN, "order-flow", String.valueOf(r.get("ORDER_NO")),
                        "恰 1 条入账/扣减流水", cnt + " 条", null));
            }
        }
        return orders.size();
    }

    /** 维度3 card-ledger：当日有流水的卡，余额/水量必须等于该卡末笔流水 AFTER（账本连续性）。 */
    private int checkCardLedger(String bizDate, List<WsReconcileDiff> diffs) {
        List<Map<String, Object>> cards = jdbc.queryForList("""
                SELECT c.ID, c.BALANCE_AMOUNT, c.BALANCE_ML,
                       f.AMOUNT_AFTER, f.ML_AFTER
                FROM ws_card c
                JOIN ws_wallet_flow f ON f.ID = (
                    SELECT MAX(f2.ID) FROM ws_wallet_flow f2 WHERE f2.CARD_ID = c.ID)
                WHERE EXISTS (SELECT 1 FROM ws_wallet_flow f3
                              WHERE f3.CARD_ID = c.ID AND f3.CREATE_TIME LIKE ?)""", bizDate + "%");
        for (Map<String, Object> r : cards) {
            if (!ObjectUtil.equal(r.get("BALANCE_AMOUNT"), r.get("AMOUNT_AFTER"))
                    || !ObjectUtil.equal(r.get("BALANCE_ML"), r.get("ML_AFTER"))) {
                diffs.add(diff(SettlementEnum.DiffType.LEDGER_BROKEN, "card-ledger", "card:" + r.get("ID"),
                        r.get("AMOUNT_AFTER") + "/" + r.get("ML_AFTER"),
                        r.get("BALANCE_AMOUNT") + "/" + r.get("BALANCE_ML"), "卡余额≠末笔流水 AFTER"));
            }
        }
        return cards.size() + checkCardFlowContinuity(bizDate, diffs);
    }

    /** 当日每笔水卡流水必须承接同卡上一笔 AFTER；只看末笔会漏掉中间账本被改写。 */
    private int checkCardFlowContinuity(String bizDate, List<WsReconcileDiff> diffs) {
        List<Map<String, Object>> flows = jdbc.queryForList("""
                SELECT * FROM (
                    SELECT f.ID, f.CARD_ID, f.CREATE_TIME,
                           f.AMOUNT_CHANGE, f.ML_CHANGE, f.AMOUNT_AFTER, f.ML_AFTER,
                           LAG(f.AMOUNT_AFTER) OVER (PARTITION BY f.CARD_ID ORDER BY f.ID) AS PREV_AMOUNT_AFTER,
                           LAG(f.ML_AFTER) OVER (PARTITION BY f.CARD_ID ORDER BY f.ID) AS PREV_ML_AFTER
                    FROM ws_wallet_flow f
                ) chain
                WHERE chain.CREATE_TIME LIKE ? AND chain.PREV_AMOUNT_AFTER IS NOT NULL""", bizDate + "%");
        for (Map<String, Object> r : flows) {
            long expectedAmount;
            long expectedMl;
            boolean overflow = false;
            try {
                expectedAmount = Math.addExact(((Number) r.get("PREV_AMOUNT_AFTER")).longValue(),
                        ((Number) r.get("AMOUNT_CHANGE")).longValue());
                expectedMl = Math.addExact(((Number) r.get("PREV_ML_AFTER")).longValue(),
                        ((Number) r.get("ML_CHANGE")).longValue());
            }
            catch (ArithmeticException e) {
                expectedAmount = 0;
                expectedMl = 0;
                overflow = true;
            }
            long actualAmount = ((Number) r.get("AMOUNT_AFTER")).longValue();
            long actualMl = ((Number) r.get("ML_AFTER")).longValue();
            if (overflow || expectedAmount != actualAmount || expectedMl != actualMl) {
                diffs.add(diff(SettlementEnum.DiffType.LEDGER_BROKEN, "card-ledger",
                        "card:" + r.get("CARD_ID") + ":flow:" + r.get("ID"),
                        overflow ? "上一笔 AFTER + 本笔变动未溢出" : expectedAmount + "/" + expectedMl,
                        actualAmount + "/" + actualMl, "水卡流水中间 AFTER 不连续"));
            }
        }
        return flows.size();
    }

    /** 维度4 split-sum：当日产生分账的订单，分账行合计必须等于口径基数（行内自洽校验）。 */
    private int checkSplitSum(String bizDate, List<WsReconcileDiff> diffs) {
        List<Map<String, Object>> orders = jdbc.queryForList("""
                SELECT s.ORDER_ID, SUM(s.SPLIT_AMOUNT) AS SPLIT_SUM,
                       MAX(o.ORDER_NO) AS ORDER_NO, MAX(o.ORDER_TYPE) AS ORDER_TYPE,
                       MAX(o.ORDER_AMOUNT) AS ORDER_AMOUNT,
                       MAX(COALESCE(flow.NET_DEBIT, 0)) AS NET_DEBIT
                FROM ws_split_record s JOIN ws_order o ON o.ID = s.ORDER_ID
                LEFT JOIN (
                    SELECT ORDER_ID, -SUM(AMOUNT_CHANGE) AS NET_DEBIT
                    FROM ws_wallet_flow
                    WHERE ORDER_ID IS NOT NULL
                    GROUP BY ORDER_ID
                ) flow ON flow.ORDER_ID = s.ORDER_ID
                WHERE s.CREATE_TIME LIKE ?
                GROUP BY s.ORDER_ID""", bizDate + "%");
        for (Map<String, Object> r : orders) {
            long splitSum = ((Number) r.get("SPLIT_SUM")).longValue();
            long netDebit = ((Number) r.get("NET_DEBIT")).longValue();
            // 售水退差后的基数不是订单预扣上限，而是同订单卡流水净实扣；少记与多记都必须报差异。
            if (splitSum != netDebit) {
                diffs.add(diff(SettlementEnum.DiffType.AMOUNT_MISMATCH, "split-sum", String.valueOf(r.get("ORDER_NO")),
                        String.valueOf(netDebit), String.valueOf(splitSum), "分账合计≠同订单卡流水净实扣"));
            }
        }
        return orders.size();
    }

    /** 维度5 income-ledger：当日有收益流水的账户，余额必须等于末笔收益流水 AFTER。 */
    private int checkIncomeLedger(String bizDate, List<WsReconcileDiff> diffs) {
        List<Map<String, Object>> accounts = jdbc.queryForList("""
                SELECT a.USER_ID, a.BALANCE_FEN, f.AFTER_FEN
                FROM ws_income_account a
                JOIN ws_income_flow f ON f.ID = (
                    SELECT MAX(f2.ID) FROM ws_income_flow f2 WHERE f2.USER_ID = a.USER_ID)
                WHERE EXISTS (SELECT 1 FROM ws_income_flow f3
                              WHERE f3.USER_ID = a.USER_ID AND f3.CREATE_TIME LIKE ?)""", bizDate + "%");
        for (Map<String, Object> r : accounts) {
            if (!ObjectUtil.equal(r.get("BALANCE_FEN"), r.get("AFTER_FEN"))) {
                diffs.add(diff(SettlementEnum.DiffType.LEDGER_BROKEN, "income-ledger", "income:" + r.get("USER_ID"),
                        String.valueOf(r.get("AFTER_FEN")), String.valueOf(r.get("BALANCE_FEN")),
                        "收益余额≠末笔流水 AFTER"));
            }
        }
        return accounts.size() + checkIncomeFlowContinuity(bizDate, diffs);
    }

    /** 当日每笔收益流水必须承接同账户上一笔 AFTER；账户等于末笔不足以证明中间账本完整。 */
    private int checkIncomeFlowContinuity(String bizDate, List<WsReconcileDiff> diffs) {
        List<Map<String, Object>> flows = jdbc.queryForList("""
                SELECT * FROM (
                    SELECT f.ID, f.USER_ID, f.CREATE_TIME, f.AMOUNT_FEN, f.AFTER_FEN,
                           LAG(f.AFTER_FEN) OVER (PARTITION BY f.USER_ID ORDER BY f.ID) AS PREV_AFTER
                    FROM ws_income_flow f
                ) chain
                WHERE chain.CREATE_TIME LIKE ? AND chain.PREV_AFTER IS NOT NULL""", bizDate + "%");
        for (Map<String, Object> r : flows) {
            long expected;
            boolean overflow = false;
            try {
                expected = Math.addExact(((Number) r.get("PREV_AFTER")).longValue(),
                        ((Number) r.get("AMOUNT_FEN")).longValue());
            }
            catch (ArithmeticException e) {
                expected = 0;
                overflow = true;
            }
            long actual = ((Number) r.get("AFTER_FEN")).longValue();
            if (overflow || expected != actual) {
                diffs.add(diff(SettlementEnum.DiffType.LEDGER_BROKEN, "income-ledger",
                        "income:" + r.get("USER_ID") + ":flow:" + r.get("ID"),
                        overflow ? "上一笔 AFTER + 本笔变动未溢出" : String.valueOf(expected),
                        String.valueOf(actual), "收益流水中间 AFTER 不连续"));
            }
        }
        return flows.size();
    }

    private WsReconcileDiff diff(SettlementEnum.DiffType type, String dimension, String bizKey,
                                 String expected, String actual, String remark) {
        return new WsReconcileDiff().setDiffType(type.getValue()).setCheckDimension(dimension)
                .setBizKey(StrUtil.brief(bizKey, 64))
                .setExpectedVal(StrUtil.brief(expected, 200))
                .setActualVal(StrUtil.brief(actual, 200))
                .setDiffRemark(remark);
    }

    /** 同账期恒一行：既有终态可抢占重跑；首次并发撞唯一键的输方直接让行。 */
    private WsReconcileTask ensureTask(String bizDate) {
        WsReconcileTask existing = getOne(Wrappers.lambdaQuery(WsReconcileTask.class)
                .eq(WsReconcileTask::getBizDate, bizDate));
        if (ObjectUtil.isNotNull(existing)) {
            // 前态条件抢占：01:30 定时器与 PC 手动触发并发时，输方直接拒绝——
            // 两个 runFor 各自「删旧差异+插新差异」交错会把差异行写成双份
            boolean claimed = update(Wrappers.lambdaUpdate(WsReconcileTask.class)
                    .eq(WsReconcileTask::getId, existing.getId())
                    .ne(WsReconcileTask::getTaskStatus, RUNNING)
                    .set(WsReconcileTask::getTaskStatus, RUNNING));
            if (!claimed) {
                throw new JbkException("该账期对账正在执行中，请稍后再试");
            }
            return existing;
        }
        try {
            WsReconcileTask fresh = new WsReconcileTask()
                    .setBizDate(bizDate).setTaskStatus(RUNNING).setCheckTotal(0).setDiffTotal(0);
            save(fresh);
            return fresh;
        }
        catch (DuplicateKeyException e) {
            // 首次并发触发时，输方事务的 RR 快照仍可能看不到胜方刚提交的任务行；
            // 撞唯一键即明确让行，禁止读回 null 或绕过 RUNNING 抢占继续执行。
            throw new JbkException("该账期对账正在执行中，请稍后再试");
        }
    }
}
