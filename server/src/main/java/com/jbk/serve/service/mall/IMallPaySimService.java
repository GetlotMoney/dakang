package com.jbk.serve.service.mall;

import com.jbk.tool.data.mall.vo.MallOrderDetailVo;

/**
 * 商城 Pay-Sim 模拟支付服务（E2E-09 S2，仅隔离环境启用）。
 *
 * <p>本服务只负责"造一条支付事实"，不直接改库存也不直接推进订单——推进恒由
 * 事实处理器完成，与真实支付通知走同一条路径。这样内部闭环验证过的链路，
 * 接真实支付时不需要换实现。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
public interface IMallPaySimService {

    /** 模拟支付成功：落事实（事务A）后驱动推进（事务B），返回订单最新详情。 */
    MallOrderDetailVo pay(Long userId, String orderNo);

    /** 查询本人订单支付状态（不产生事实）。 */
    MallOrderDetailVo payStatus(Long userId, String orderNo);
}
