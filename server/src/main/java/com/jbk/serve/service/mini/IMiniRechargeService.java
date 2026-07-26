package com.jbk.serve.service.mini;

import com.jbk.tool.data.mini.bo.MiniRechargeCreateBo;
import com.jbk.tool.data.mini.vo.MiniRechargeOrderVo;

/**
 * 小程序充值订单服务（L2-ORDER）。
 *
 * <p>该服务只创建“待支付”订单与支付单，<b>不模拟支付成功</b>、不入账、不修改卡余额。</p>
 */
public interface IMiniRechargeService {

    /**
     * 创建（或幂等命中）充值订单。
     *
     * @param bo     入参（cardId/packageId/requestId）
     * @param userId 会话登录人，调用方从 StpKit 取，禁止来自前端
     */
    MiniRechargeOrderVo create(MiniRechargeCreateBo bo, Long userId);
}
