package com.jbk.serve.service.mini.notify;

/**
 * 订阅消息发送适配器：本轮禁真实外呼（任务书第二节），接口隔离让 Worker 的分流/退避/租约
 * 可用假适配器完整验证。返回分类结果而非抛异常——「没订阅」与「微信 5xx」归宿相反，
 * 用异常表达会把重试判据藏进异常文案里。
 *
 * @author dakang
 * @since 2026-08-12
 */
public interface WechatSubscribeSender {

    /** 发送结果分类。每一类的归宿见 {@code WechatNotifyWorker} 的表格。 */
    enum Kind {
        /** 微信侧确认已接受。 */
        SENT,
        /** 用户没有可用的订阅授权额度——订阅消息是一次授权一次下发。 */
        NO_SUBSCRIPTION,
        /** 可重试：网络抖动、限流、微信侧 5xx。 */
        RETRYABLE,
        /** 不可重试：模板已停用、参数不合法、账号权限问题。重试只会浪费额度。 */
        PERMANENT,
    }

    /**
     * 一次发送的结果。
     *
     * @param kind   结果分类，决定 Worker 怎么落状态
     * @param reason 人类可读的原因，只进 LAST_ERROR 与日志；<b>绝不参与任何判定</b>
     */
    record SendOutcome(Kind kind, String reason) {

        public static SendOutcome sent() {
            return new SendOutcome(Kind.SENT, null);
        }

        public static SendOutcome noSubscription() {
            return new SendOutcome(Kind.NO_SUBSCRIPTION, "用户无可用订阅授权额度");
        }

        public static SendOutcome retryable(String reason) {
            return new SendOutcome(Kind.RETRYABLE, reason);
        }

        public static SendOutcome permanent(String reason) {
            return new SendOutcome(Kind.PERMANENT, reason);
        }
    }

    /**
     * 发送一条订阅消息。
     *
     * <p>实现<b>不得</b>抛异常表达业务失败——任何预期内的失败都要落成 {@link SendOutcome}。
     * 抛出的异常会被 Worker 当成未知情况处理，那是给真正意外准备的路径。</p>
     *
     * @param openid      收件人 openid，由 Worker 现查后传入，不落库
     * @param templateId  模板 ID，来自环境配置
     * @param payloadJson 模板数据快照（业务事务内冻结）
     */
    SendOutcome send(String openid, String templateId, String payloadJson);
}
