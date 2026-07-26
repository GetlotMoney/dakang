package com.jbk.serve.service.mini.recharge;

/**
 * 支付来源的**受信任服务端适配器**（L2 契约 §5.2）。
 *
 * <p>PAY_SOURCE 是支付单权威来源，一旦创建不可改变：微信适配器只能写 WECHAT，
 * Pay-Sim 适配器只能写 PAY_SIM。前端、普通请求或支付报文自报字段**均不得决定来源**——
 * 因此该值只能由本接口的服务端实现给出，禁止出现在任何入参 BO 里。</p>
 *
 * <p>L2-T 引入 Pay-Sim 时新增一个受双门控（dev/test profile + 显式开关）的实现，
 * 生产不注册该 Bean。</p>
 */
public interface IRechargePaySourceAdapter {

    /** 支付来源：1 微信。 */
    int WECHAT = 1;
    /** 支付来源：2 Pay-Sim（仅隔离测试环境）。 */
    int PAY_SIM = 2;

    /** 当前生效的支付来源常量。 */
    int currentSource();
}
