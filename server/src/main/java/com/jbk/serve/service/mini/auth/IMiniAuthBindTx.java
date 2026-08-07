package com.jbk.serve.service.mini.auth;

/**
 * L2-AUTH 绑定的独立事务边界（复审 P0-2）。
 *
 * <p>把「数据库绑定」与「签发 Token」严格分离：本 Bean 的事务方法负责所有身份读+写，
 * 并在其<b>提交成功后</b>返回；编排层只有拿到返回值才签发 KH_USER Token。任何写 0 行、
 * 冲突或提交失败都以异常结束，绝不会先签发会话再回滚。</p>
 */
public interface IMiniAuthBindTx {

    /**
     * 在独立事务内完成手机号↔openid 绑定，提交成功后返回绑定用户。
     *
     * @param openid 已由票据校验过的微信 openid
     * @param phone  服务端解析出的手机号
     * @return 绑定成功的用户（供编排层签发会话）
     */
    BoundUser bind(String openid, String phone);

    /**
     * 在独立事务内完成「仅微信身份建号」：手机号留空（NULL），提交成功后返回。
     *
     * <p>用于小程序主体未通过微信认证、{@code getPhoneNumber} 组件被平台禁用因而拿不到 phoneCode 的场景。
     * 同 openid 重复调用幂等（已存在即返回既有账号），并发由 {@code uk_user_wechat_xcx_openid} 兜底。</p>
     *
     * @param openid 已由 code2session 换取的微信 openid
     * @return 建号或既有的用户（供编排层签发会话）
     */
    BoundUser registerByOpenid(String openid);

    /**
     * 在独立事务内为「已登录但手机号为空」的账号补绑手机号，提交成功后返回。
     *
     * <p>只允许 {@code USER_PHONE IS NULL} → 非空这一个方向；已绑号账号的换绑不走本路径。</p>
     *
     * @param userId 当前会话用户 ID
     * @param phone  服务端解析出的手机号
     * @return 补绑后的用户
     */
    BoundUser bindPhoneToCurrentUser(Long userId, String phone);
}
