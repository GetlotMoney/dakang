package com.jbk.serve.service.mini.auth;

/**
 * 绑定事务提交成功后回传给编排层的最小账号信息（用于签发会话与组装上下文）。
 * 仅承载建立会话所需字段，绝不含 openid / session_key。
 *
 * @param newlyCreated    本次调用**新建**了账号行
 * @param phoneNewlyBound 本次调用让该账号**首次**拥有手机号（NULL → 非空）。
 *                        这是 D-418 赠卡的唯一判据，与 newlyCreated 刻意分开：
 *                        游客态下账号在 openid 首登时就建好了（newlyCreated 恒 false），
 *                        而存量老用户首次用微信登录时账号早有手机号（本字段为 false）——
 *                        两种情形用 newlyCreated 都判不对，一个漏发、一个错发。
 */
public record BoundUser(Long id, String userName, String userPhone,
                        boolean newlyCreated, boolean phoneNewlyBound) {

    /** 既有幂等/复用返回路径：两个标志都为 false（不触发注册送，D-418）。 */
    public BoundUser(Long id, String userName, String userPhone) {
        this(id, userName, userPhone, false, false);
    }

    /** 新建账号且同时落定手机号（strict 态的注册链）。 */
    public BoundUser(Long id, String userName, String userPhone, boolean newlyCreated) {
        this(id, userName, userPhone, newlyCreated, newlyCreated);
    }
}
