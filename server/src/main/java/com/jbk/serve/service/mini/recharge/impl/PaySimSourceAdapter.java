package com.jbk.serve.service.mini.recharge.impl;

import com.jbk.serve.service.mini.recharge.IRechargePaySourceAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Pay-Sim 支付来源适配器（仅 {@code mini.pay-sim.enabled=true} 时注册）。
 *
 * <p>与 {@link WechatPaySourceAdapter} 用同一个属性做 <b>互斥</b> 门控：
 * 一个 {@code havingValue="true"}、一个 {@code havingValue="false" matchIfMissing=true}，
 * 因此任何时刻容器里有且只有一个来源适配器。这样做的意义是——
 * 模拟支付产生的订单会被 PAY_SOURCE=2 永久标记，绝不可能混进真实微信收款的对账口径里。</p>
 */
@Component
@ConditionalOnProperty(name = "mini.pay-sim.enabled", havingValue = "true")
public class PaySimSourceAdapter implements IRechargePaySourceAdapter {

    @Override
    public int currentSource() {
        return PAY_SIM;
    }
}
