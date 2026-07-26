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
}
