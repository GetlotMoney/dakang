package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.mapper.mall.WsMallOrderMapper;
import com.jbk.serve.service.mall.IMallOrderService;
import com.jbk.serve.service.mall.IMallPayFactService;
import com.jbk.serve.service.mall.IMallPayQueryAdapter;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商城超时关单 Worker（E2E-09 S2）。
 *
 * <p><b>本地到期不构成关闭依据。</b>扫描只挑出"我方认为该催了"的订单，是否关闭
 * 必须由支付方查单答复 CLOSED 决定；查不到适配器、或答复不是 CLOSED，一律不动。
 * 凭本地时钟关单会把一笔实际已支付的订单连同库存一起放掉，用户钱货两空。</p>
 *
 * <p>关闭前先把 CLOSED 事实落库（查单渠道），让"为什么关的"有据可查，
 * 而不是只在日志里留一行。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Slf4j
@Component
public class MallOrderExpireWorker {

    private static final int BATCH = 50;
    /** 查单事实键前缀：与 Pay-Sim 主动支付渠道命名空间不重叠。 */
    static final String QUERY_EVENT_KEY_PREFIX = "MALLQ:";

    @Autowired
    private WsMallOrderMapper orderMapper;
    @Autowired
    private IMallOrderService orderService;
    @Autowired
    private IMallPayFactService payFactService;
    /** 无查单适配器时为 null（生产未接真实支付）：此时关单链路整体停摆。 */
    @Autowired(required = false)
    private IMallPayQueryAdapter payQueryAdapter;

    @Scheduled(fixedDelay = 60_000, initialDelay = 70_000)
    public void closeExpired() {
        runOnce(DateUtils.time());
    }

    /** 可测入口：返回本轮实际关闭的订单数。 */
    int runOnce(String now) {
        if (ObjectUtil.isNull(payQueryAdapter)) {
            log.debug("未装配商城支付查单适配器，跳过超时关单");
            return 0;
        }
        List<String> orderNos = orderMapper.scanExpiredPendingOrderNos(now, BATCH);
        int closed = 0;
        for (String orderNo : orderNos) {
            try {
                if (closeOne(orderNo, now)) {
                    closed++;
                }
            }
            catch (RuntimeException isolated) {
                log.error("商城超时关单异常 orderNo={}", orderNo, isolated);
            }
        }
        return closed;
    }

    private boolean closeOne(String orderNo, String now) {
        String tradeState = payQueryAdapter.queryTradeState(orderNo);
        if (!MallEnum.TradeState.CLOSED.equals(tradeState)) {
            // NOTPAY 说明付款窗还在支付方那边算着；SUCCESS/null 更不能关
            return false;
        }
        payFactService.recordFact(
                payQueryAdapter.paySource(),
                MallEnum.FactChannel.QUERY.getValue(),
                QUERY_EVENT_KEY_PREFIX + orderNo,
                orderNo,
                MallEnum.TradeState.CLOSED,
                null, null, null,
                MallEnum.VerifyMethod.PAY_SIM_INTERNAL,
                "{\"channel\":\"query\",\"orderNo\":\"" + orderNo + "\",\"tradeState\":\"CLOSED\"}");
        orderService.closeByAuthority(orderNo, "支付超时关闭（支付方查单 CLOSED）");
        log.info("商城订单超时关闭 orderNo={} now={}", orderNo, now);
        return true;
    }
}
