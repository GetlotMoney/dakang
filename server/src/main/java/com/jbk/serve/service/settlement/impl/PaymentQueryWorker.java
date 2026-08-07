package com.jbk.serve.service.settlement.impl;

import com.jbk.serve.service.mini.IMiniPaySimService;
import com.jbk.serve.service.mini.recharge.IRechargePaySourceAdapter;
import com.jbk.tool.data.mini.bo.MiniPaySimBo;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 主动查单定时器（E2E-08 包C / REQ-042）：扫描「已过支付截止时间仍 payment1/order1」的
 * 充值单，逐单走 {@link IMiniPaySimService#query} 造权威查单事实（FACT_CHANNEL=2）——
 * 与用户手动查单同一管道零新路径；关单由查单事实经既有处理器驱动（1/1→4/5），
 * 本 Worker 绝不本地改状态（契约：关单必须由支付方权威结果驱动）。
 *
 * <p>过期判定直接用创单时冻结的 PAY_EXPIRE_TIME 列比对当前时钟——不另写时间算法
 * （任务书口径8：与 RechargePayExpire 同一冻结值的消费侧，口径不可能分叉）。
 * 与 Pay-Sim 同开关：真实微信查单接入时替换为微信适配器的同名扫描。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mini.pay-sim.enabled", havingValue = "true")
public class PaymentQueryWorker {

    private static final int BATCH = 20;

    @Autowired
    private IMiniPaySimService paySimService;
    @Autowired
    private JdbcTemplate jdbc;

    @Scheduled(fixedDelay = 60_000)
    public void queryExpiredUnpaid() {
        String now = DateUtils.time();
        // PAY_SOURCE 过滤到本适配器（Pay-Sim）：异源支付单在 resolve 处必拒，不过滤的话
        // 一批永久失败行会每分钟占满 LIMIT 队头，把后续超期单彻底饿死
        // LIMIT 用普通串拼接：文本块会剥每行尾随空格，"LIMIT """ + N 会拼成 LIMIT20（验收实抓）
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.ORDER_NO, o.USER_ID
                FROM ws_payment p JOIN ws_order o ON o.ORDER_NO = p.ORDER_NO
                WHERE p.PAY_STATUS = 1 AND o.ORDER_STATUS = 1 AND p.PAY_EXPIRE_TIME < ?
                  AND p.PAY_SOURCE = ?
                ORDER BY p.ID ASC
                """ + "LIMIT " + BATCH, now, IRechargePaySourceAdapter.PAY_SIM);
        for (Map<String, Object> row : rows) {
            String orderNo = String.valueOf(row.get("ORDER_NO"));
            try {
                // 以订单归属人身份查单：与该用户手动点查单完全等价（resolve 的归属校验因此天然通过）
                MiniPaySimBo bo = new MiniPaySimBo();
                bo.setOrderNo(orderNo);
                paySimService.query(bo, ((Number) row.get("USER_ID")).longValue());
            }
            catch (Exception e) {
                // 单笔失败不阻塞同批：查单事实管道自身有重试收件箱，这里只记日志下轮再扫
                log.warn("超期查单失败，下轮重扫：orderNo={} {}", orderNo, e.getMessage());
            }
        }
        if (!rows.isEmpty()) {
            log.info("主动查单扫描：本轮处理 {} 笔超期未付单", rows.size());
        }
    }
}
