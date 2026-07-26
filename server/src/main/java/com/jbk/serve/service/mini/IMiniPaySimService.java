package com.jbk.serve.service.mini;

import com.jbk.tool.data.mini.bo.MiniPaySimBo;
import com.jbk.tool.data.mini.vo.MiniPaySimVo;

/**
 * Pay-Sim 模拟支付（L2-T，仅隔离测试环境）。
 *
 * <p>它<b>不是</b>一条捷径：模拟支付只负责造出一条与真实微信通知同构的「支付事实」，
 * 之后的认定、入账、幂等全部走与真实回调完全相同的处理器。
 * 因此这里验证通过的链路，接真实微信时不需要另写一套。</p>
 */
public interface IMiniPaySimService {

    MiniPaySimVo pay(MiniPaySimBo bo, Long userId);

    /**
     * 模拟<b>支付方主动查单</b>：返回并落库一条 NOTPAY 或 CLOSED 的查询事实（FACT_CHANNEL=2）。
     *
     * <p>它替代的是真实微信的查单接口（L2-WX 尚未接入），<b>不是本地超时关单</b>。
     * 契约要求关单必须由支付方权威结果驱动，因此这里造出的事实同样交给
     * {@code IRechargePayFactService} 那一条处理路径——Pay-Sim 不开小灶。</p>
     */
    MiniPaySimVo query(MiniPaySimBo bo, Long userId);
}
