package com.jbk.serve.service.mini.recharge.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.service.mini.recharge.NewCardExpiry;
import com.jbk.serve.service.mini.recharge.RechargeCredit;
import com.jbk.serve.service.mini.recharge.RechargeExpiry;
import com.jbk.serve.service.mini.recharge.RechargeSnapshot;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;

/** 充值幂等命中与失败落痕共用的账本完整性校验。 */
@Component
final class RechargeLedgerVerifier {

    private static final DateTimeFormatter TIME = com.jbk.tool.utils.DateUtils.COMPACT_FORMATTER;

    void requireNoRechargeFlow(WsOrder order, WsCard card, List<WsWalletFlow> flows,
                               long orderRechargeFlowCount, long bizKeyCount) {
        if (orderRechargeFlowCount != 0) {
            throw new JbkException("订单尚未完成但已存在关联充值流水");
        }
        if (bizKeyCount != 0) {
            throw new JbkException("订单尚未完成但充值幂等键已被占用");
        }
        validateRowsAndTerminal(card, flows);
    }

    void requireAlreadyCredited(WsOrder order, WsCard card, List<WsWalletFlow> flows,
                                RechargeCredit credit, RechargeSnapshot.Parsed snapshot,
                                String paySuccessTime) {
        List<WsWalletFlow> ordered = validateRowsAndTerminal(card, flows);
        List<WsWalletFlow> matches = ordered.stream()
                .filter(row -> StrUtil.equals(row.getBizIdempotencyKey(), bizKey(order)))
                .toList();
        if (matches.size() != 1) {
            throw new JbkException("已完成订单的充值流水必须恰好一条");
        }
        WsWalletFlow flow = matches.get(0);
        if (!ObjectUtil.equals(flow.getDataStatus(), 0)
                || !ObjectUtil.equals(flow.getCardId(), card.getId())
                || !ObjectUtil.equals(flow.getUserId(), order.getUserId())
                || !ObjectUtil.equals(flow.getOrderId(), order.getId())
                || !ObjectUtil.equals(flow.getFlowType(), 1)
                || !ObjectUtil.equals(flow.getAmountChange(), credit.amountFen())
                || !ObjectUtil.equals(flow.getMlChange(), credit.ml())) {
            throw new JbkException("已完成订单的充值流水共键或权益值不一致");
        }
        int targetIndex = ordered.indexOf(flow);
        // 新规则不追溯否定目标充值之前的历史快照；但目标流水必须承接它的直接前一笔，
        // 并且从目标开始的所有后续流水必须逐笔连续到当前卡终值。
        int continuityStart = Math.max(1, targetIndex);
        for (int i = continuityStart; i < ordered.size(); i++) {
            verifyAdjacent(ordered.get(i - 1), ordered.get(i));
        }
        verifyCompletedExpiry(card, snapshot, paySuccessTime);
    }

    /**
     * 完成态只读幂等核验必须覆盖有效期权益。有限套餐允许后续充值继续延长，因此校验本单可证明的
     * 最低下限；永久套餐必须始终保持 EXPIRE_TIME=NULL，不能把有限卡伪装成永久权益已完成。
     *
     * <p>下限公式按快照类型分流（决策 A1，两套规则各管各的）：L2-B 充值单是
     * {@code max(expireTimeAtCreate, paySuccessTime) + expireDays}；L2-A 首次购卡单没有既有卡，
     * 下限即 {@link NewCardExpiry} 的 {@code paySuccessTime + expireDays}——若误用 L2-B 公式，
     * purchase 快照的 {@code expireTimeAtCreate=null} 会直接抛格式异常，把合法的完成态重放打成人工对账。</p>
     */
    private void verifyCompletedExpiry(WsCard card, RechargeSnapshot.Parsed snapshot, String paySuccessTime) {
        Integer expireDays = snapshot.expireDays();
        if (expireDays == null) {
            if (card.getExpireTime() != null) {
                throw new JbkException("已完成永久套餐订单的水卡有效期必须为空");
            }
            return;
        }
        if (StrUtil.isBlank(card.getExpireTime())) {
            throw new JbkException("已完成有限套餐订单缺少有效期权益");
        }
        String minimum = snapshot.purchaseMode() != null
                ? NewCardExpiry.compute(paySuccessTime, expireDays)
                : RechargeExpiry.extend(snapshot.expireTimeAtCreate(), paySuccessTime, expireDays);
        if (parseTime(card.getExpireTime(), "水卡当前有效期").isBefore(parseTime(minimum, "本单有效期最低下限"))) {
            throw new JbkException("已完成有限套餐订单的有效期权益低于本单最低下限");
        }
    }

    private LocalDateTime parseTime(String value, String label) {
        if (value == null || value.length() != 14) {
            throw new JbkException(label + "格式非法");
        }
        try {
            return LocalDateTime.parse(value, TIME);
        } catch (DateTimeParseException e) {
            throw new JbkException(label + "格式非法");
        }
    }

    private List<WsWalletFlow> validateRowsAndTerminal(WsCard card, List<WsWalletFlow> source) {
        List<WsWalletFlow> flows = source.stream()
                .sorted(Comparator.comparing(WsWalletFlow::getId))
                .toList();
        for (WsWalletFlow row : flows) {
            if (!ObjectUtil.equals(row.getDataStatus(), 0)
                    || !ObjectUtil.equals(row.getCardId(), card.getId())
                    || row.getAmountChange() == null || row.getMlChange() == null
                    || row.getAmountAfter() == null || row.getMlAfter() == null) {
                throw new JbkException("目标卡账本存在删除态、错位或空值流水");
            }
        }
        if (!flows.isEmpty()) {
            WsWalletFlow last = flows.get(flows.size() - 1);
            if (!ObjectUtil.equals(last.getAmountAfter(), card.getBalanceAmount())
                    || !ObjectUtil.equals(last.getMlAfter(), card.getBalanceMl())) {
                throw new JbkException("目标卡终值与最后一笔有效流水不一致");
            }
        }
        return flows;
    }

    private void verifyAdjacent(WsWalletFlow previous, WsWalletFlow current) {
        long expectedAmount;
        long expectedMl;
        try {
            expectedAmount = Math.addExact(previous.getAmountAfter(), current.getAmountChange());
            expectedMl = Math.addExact(previous.getMlAfter(), current.getMlChange());
        } catch (ArithmeticException e) {
            throw new JbkException("目标卡账本连续性计算溢出");
        }
        if (!ObjectUtil.equals(current.getAmountAfter(), expectedAmount)
                || !ObjectUtil.equals(current.getMlAfter(), expectedMl)) {
            throw new JbkException("目标充值流水或其后续账本 AFTER 不连续");
        }
    }

    private String bizKey(WsOrder order) {
        return RechargeCreditTxImpl.bizKey(order.getOrderNo());
    }
}
