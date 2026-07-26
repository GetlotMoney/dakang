package com.jbk.serve.service.mini;

import com.jbk.tool.data.mini.bo.MiniPayStatusBo;
import com.jbk.tool.data.mini.vo.MiniPayStatusVo;

/** 充值订单支付状态查询（L2 契约 §9.1）。 */
public interface IMiniPayStatusService {

    /**
     * @param userId 会话登录人，调用方从 StpKit 取，禁止来自前端
     */
    MiniPayStatusVo query(MiniPayStatusBo bo, Long userId);
}
