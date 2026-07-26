package com.jbk.serve.service.mini;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.trade.bo.CreateWaterOrderBo;
import com.jbk.tool.data.trade.bo.MiniOrderDetailBo;
import com.jbk.tool.data.trade.bo.MiniOrderQueryBo;
import com.jbk.tool.data.trade.vo.OrderDetailVo;
import com.jbk.tool.data.trade.vo.OrderItemVo;

/**
 * 小程序 C 端订单服务（L1b 扫码取水下单：创建+分页+详情）。
 * <p>下单人/查询范围一律由 KH_USER 会话取，禁收前端 userId（铁律6）。</p>
 *
 * @author dakang
 * @since 2026-07-19
 */
public interface IMiniOrderService {

    /**
     * 创建扫码取水订单（余额/水量支付即时扣款）。幂等：同 scanSessionId 重复提交返同单。
     *
     * @param bo     下单入参
     * @param userId 登录用户ID（会话取）
     */
    OrderDetailVo createWaterOrder(CreateWaterOrderBo bo, Long userId);

    /** 分页查询本人订单（强制 USER_ID=登录人）。 */
    PageDataVo<OrderItemVo> pageMyOrders(MiniOrderQueryBo bo, Long userId);

    /** 查询本人订单详情（优先按 orderNo，校验归属，非本人拒绝）。 */
    OrderDetailVo getMyOrderDetail(MiniOrderDetailBo bo, Long userId);
}
