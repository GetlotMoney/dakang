package com.jbk.serve.service.mini.auth;

/**
 * 绑定事务提交成功后回传给编排层的最小账号信息（用于签发会话与组装上下文）。
 * 仅承载建立会话所需字段，绝不含 openid / session_key。
 */
public record BoundUser(Long id, String userName, String userPhone) {
}
