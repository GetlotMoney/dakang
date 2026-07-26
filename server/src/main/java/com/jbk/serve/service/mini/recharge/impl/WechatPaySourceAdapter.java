package com.jbk.serve.service.mini.recharge.impl;

import com.jbk.serve.service.mini.recharge.IRechargePaySourceAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认支付来源适配器：微信。
 *
 * <p>曾用 {@code @ConditionalOnMissingBean} 做覆盖门控——那是错的：该条件只在自动配置类中可靠，
 * 放在被组件扫描的 {@code @Component} 上时求值依赖注册顺序，L2-T 引入 Pay-Sim 后可能两个 Bean
 * 并存启动失败，或 Pay-Sim 被静默忽略而把模拟单记成真实微信收款（对账最坏方向）。</p>
 *
 * <p>改为显式属性门控：本 Bean 仅在 {@code mini.pay-sim.enabled} 非 true 时注册；
 * L2-T 的 Pay-Sim 适配器用 {@code havingValue="true"} 互斥注册，两者不可能同时存在。</p>
 */
@Component
@ConditionalOnProperty(name = "mini.pay-sim.enabled", havingValue = "false", matchIfMissing = true)
public class WechatPaySourceAdapter implements IRechargePaySourceAdapter {

    @Override
    public int currentSource() {
        return WECHAT;
    }
}
