package com.jbk.serve.controller.mall;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.jbk.serve.service.mall.IMallLogisticsFactService;
import com.jbk.serve.service.mall.IMallPayApplyTx;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.data.mall.bo.MallLogisticsSimEventBo;
import com.jbk.tool.data.mall.po.WsMallLogisticsEvent;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Logistics-Sim 物流事件入口（E2E-09 L1，仅隔离环境启用）。顺序不可换：先验签 → 再去重 →
 * 再落事实 → 最后才推进——反过来伪造报文能在被识破前先推状态；验签在控制器完成，
 * 各承运商签名算法不同而事实服务只有一套。本端点只产生物流事实、不模拟业务结果，
 * 推进由事务B 锁内重读共键后决定，与真实承运商回调同形。
 * 开关 {@code mall.logistics-sim.enabled} 缺省与生产恒 false；关闭时本控制器不注册。
 *
 * @author dakang
 * @since 2026-08-11
 */
@Slf4j
@RestController
@RequestMapping("/mall/logistics")
@Tag(name = "商城物流（内部模拟）")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.logistics-sim.enabled", havingValue = "true")
public class MallLogisticsSimController {

    /** 事实渠道：3 内部模拟（1 回调推送、2 主动查询留给真实承运商接入）。 */
    private static final int FACT_CHANNEL_SIM = 3;
    /** 验签方式：1 内部模拟签名（2 留给承运方签名）。 */
    private static final int VERIFY_METHOD_SIM = 1;

    private final IMallLogisticsFactService factService;

    /**
     * 模拟承运方的共享密钥。
     *
     * <p>只用于隔离环境的签名自校验，不是任何真实承运商的凭据。配置缺省值是刻意的：
     * 本控制器只在模拟开关打开时注册，而那只发生在隔离环境。</p>
     */
    @Value("${mall.logistics-sim.secret:dakang-logistics-sim}")
    private String simSecret;

    @PostMapping("/sim-event")
    @Operation(summary = "投递一条模拟物流事件（先验签再落事实，不直接改订单）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    @LogOperation
    public R<String> simEvent(@Validated @RequestBody MallLogisticsSimEventBo bo) {
        String expected = sign(bo);
        if (!StrUtil.equals(expected, bo.getSignature())) {
            // 验签失败一律拒绝，且**不落库**：连一条「签名不对的事实」都不留，
            // 因为收件箱里的每一条都会被重投 Worker 反复尝试
            log.warn("模拟物流事件验签失败 waybill={} eventKey={}",
                    bo.getWaybillNo(), bo.getProviderEventKey());
            return R.error("物流事件验签失败，已拒绝");
        }
        WsMallLogisticsEvent event = factService.recordFact(
                bo.getProviderCode(), FACT_CHANNEL_SIM,
                bo.getProviderEventKey(), bo.getWaybillNo(), bo.getEventState(),
                bo.getEventTime(), bo.getEventDesc(), VERIFY_METHOD_SIM, bo.rawBody());
        IMallPayApplyTx.Outcome outcome = factService.process(event.getId());
        return R.ok(outcome.code().name() + "：" + StrUtil.blankToDefault(outcome.message(), ""));
    }

    /**
     * 签名口径：与模拟承运方约定的字段顺序拼接后取 sha256。
     *
     * <p>字段顺序写死在这里而不是遍历 Map：遍历顺序一变签名就变，
     * 而那种失败在测试里是偶发的、在演示现场是必现的。</p>
     */
    private String sign(MallLogisticsSimEventBo bo) {
        String raw = StrUtil.join(":", bo.getProviderCode(), bo.getProviderEventKey(),
                bo.getWaybillNo(), bo.getEventState(), bo.getEventTime(), simSecret);
        return DigestUtil.sha256Hex(raw);
    }
}
