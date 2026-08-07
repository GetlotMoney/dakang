package com.jbk.serve.service.aftersale.refund.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONUtil;
import com.jbk.serve.mapper.aftersale.WsRefundMapper;
import com.jbk.serve.service.aftersale.refund.IRefundFactService;
import com.jbk.serve.service.aftersale.refund.IRefundSourceAdapter;
import com.jbk.serve.service.aftersale.refund.RefundFact;
import com.jbk.tool.consts.aftersale.RefundEnum;
import com.jbk.tool.data.aftersale.bo.RefundSimNotifyBo;
import com.jbk.tool.data.aftersale.po.WsRefund;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Refund-Sim 事实产生器（仅 {@code mini.refund-sim.enabled=true} 时注册）。
 *
 * <h3>它模拟的是「服务方的答复」，不是「退款结果」</h3>
 * <p>R0-8：Refund-Sim 只模拟退款服务方事实，<b>不直接改退款成功</b>。
 * 因此本类做的全部事情是造出一条与真实通知同构的事实、落进收件箱，
 * 然后交给同一个 {@link IRefundFactService#process} 去核验推进——
 * 与真实微信通知走的是<b>同一条</b>管道，不存在「模拟专用的推进捷径」。
 * 这也意味着模拟事实同样要过金额、订单号、服务方单号的交叉核对。</p>
 *
 * <h3>金额与来源不取自入参</h3>
 * <p>见 {@link RefundSimNotifyBo}：允许调用方传金额，模拟器就成了任意金额退款的入口。
 * 正常路径下金额取自本地退款单；{@code overrideAmountFen} 只用于<b>刻意构造</b>
 * 「错金额事实」以验证它会被挡下，那条路径的终点是转人工，不是退款成功。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mini.refund-sim.enabled", havingValue = "true")
public class RefundSimFactProducer {

    private static final int DEFAULT_SEQ = 1;

    private final WsRefundMapper refundMapper;
    private final IRefundFactService factService;
    private final IRefundSourceAdapter refundSourceAdapter;

    /**
     * 造一条模拟退款事实并立即消费。
     *
     * @return 事实消费结局，供调用方与冒烟脚本判读
     */
    public IRefundFactService.Outcome notifyFact(RefundSimNotifyBo bo) {
        if (bo == null || StrUtil.isBlank(bo.getRefundNo())) {
            throw new JbkException("Refund-Sim 事实入参缺失");
        }
        // 双重确认来源：本 Bean 只在开关为真时注册，而适配器也必须同时是 Sim。
        // 两者理论上不可能不一致（同一个开关），这里显式断言是为了让「有人给微信适配器
        // 加了别的注册条件」这类改动在测试期就炸掉，而不是把模拟事实记成微信退款。
        if (refundSourceAdapter.currentSource() != IRefundSourceAdapter.REFUND_SIM) {
            throw new JbkException("Refund-Sim 事实产生器与当前退款来源适配器不一致，拒绝产生事实");
        }

        WsRefund refund = refundMapper.selectByRefundNo(bo.getRefundNo());
        if (ObjectUtil.isNull(refund)) {
            throw new JbkException("退款单不存在，无法模拟其退款事实：" + bo.getRefundNo());
        }
        if (StrUtil.isBlank(refund.getProviderRefundId())) {
            throw new JbkException("退款单尚未受理（无服务方退款单号），此时不可能收到服务方事实");
        }

        String state = StrUtil.blankToDefault(bo.getResult(), RefundEnum.FactState.SUCCESS);
        int seq = bo.getFactSeq() == null ? DEFAULT_SEQ : bo.getFactSeq();
        long amount = bo.getOverrideAmountFen() != null ? bo.getOverrideAmountFen() : refund.getRefundAmount();
        String orderNo = StrUtil.blankToDefault(bo.getOverrideOrderNo(), refund.getOrderNo());
        String now = DateUtils.time();

        // 事实键 = 退款单号 + 序号：同一退款单的第 N 条模拟事实恒定同键，
        // 因此「同事实重复三次」在库里只会有一行（场景13）。序号由调用方给，
        // 用随机数会让这条场景变成不可测。
        String eventKey = "SIM:" + refund.getRefundNo() + ":" + seq;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "REFUND_SIM");
        body.put("out_refund_no", refund.getRefundNo());
        body.put("refund_id", refund.getProviderRefundId());
        body.put("out_trade_no", orderNo);
        body.put("refund_status", state);
        body.put("amount_fen", amount);
        body.put("currency", refund.getCurrency());
        body.put("success_time", now);
        body.put("fact_seq", seq);
        String rawBody = JSONUtil.toJsonStr(body);

        boolean success = RefundEnum.FactState.SUCCESS.equals(state);
        RefundFact fact = new RefundFact(
                IRefundSourceAdapter.REFUND_SIM,
                RefundEnum.FactChannel.REFUND_SIM,
                eventKey,
                refund.getRefundNo(),
                orderNo,
                state,
                refund.getProviderRefundId(),
                amount,
                success ? refund.getCurrency() : null,
                success ? now : null,
                rawBody,
                RefundEnum.VerifyMethod.REFUND_SIM_HMAC);

        Long eventId = factService.ingest(fact, now);
        return factService.process(eventId, now);
    }

    /** 供测试与排查核对：模拟正文的摘要口径与 ingest 内一致。 */
    static String digest(String rawBody) {
        return SecureUtil.sha256(rawBody);
    }
}
