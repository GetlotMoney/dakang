package com.jbk.serve.service.mall;

import com.jbk.tool.data.mall.po.WsMallLogisticsEvent;

/**
 * 物流事实服务（E2E-09 L1，事实段）。
 *
 * <p>与支付/退款事实同形的两段式：本段只把外部事实落库留证，推进交给
 * {@link IMallLogisticsApplyTx}（REQUIRES_NEW）。两者绑在一个事务里，
 * 一次推进失败就会把已经收到的外部事实一起回滚掉——而承运方不会再推一遍。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
public interface IMallLogisticsFactService {

    /**
     * 落一条物流事实（事务A）。
     *
     * <p>调用前必须已完成验签：本方法只负责去重与留证，不判断报文真伪——
     * 把验签放进来会让「未验签就落库」变成一个只差一行代码的可能。</p>
     */
    WsMallLogisticsEvent recordFact(String providerCode, int factChannel, String providerEventKey,
                                    String waybillNo, String eventState, String eventTime,
                                    String eventDesc, int verifyMethod, String rawBody);

    /** 认领并推进一条事实；返回推进结论。 */
    IMallPayApplyTx.Outcome process(Long eventId);
}
