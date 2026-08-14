package com.jbk.serve.service.mall;

/**
 * 商城支付事实的资金推进段（事务B，E2E-09 S2）。
 *
 * <p>与事实落库（事务A）分离：事实一旦收到就必须留存，而推进可以失败并重试。
 * 若把两者放进同一事务，推进失败会把事实一起回滚——外部支付已经发生，我们却
 * 当作从未收到，这是最不可接受的一种数据丢失。</p>
 *
 * <p>入参只有事实主键：订单、支付单、金额全部在事务内按共键从库重读，
 * 调用方无法伪造任何一项。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
public interface IMallPayApplyTx {

    /** 推进结果码。 */
    enum Code {
        /** 本次成功推进：实销完成、订单转已支付。 */
        APPLIED,
        /** 已处理过（订单已推进/实销流水已存在）：幂等返回，零副作用。 */
        ALREADY,
        /** 与订单/支付单对不上，需人工对账：不动库存不动订单。 */
        RECONCILE,
        /** 瞬时失败已安排重试：事实与支付单原样保留，由 Worker 再来一次。 */
        RETRY
    }

    /** 推进结果：code 决定事实的终态，message 落 LAST_ERROR 供排障。 */
    record Outcome(Code code, String message) {

        public static Outcome applied() {
            return new Outcome(Code.APPLIED, null);
        }

        public static Outcome already(String message) {
            return new Outcome(Code.ALREADY, message);
        }

        public static Outcome reconcile(String message) {
            return new Outcome(Code.RECONCILE, message);
        }

        public static Outcome retry(String message) {
            return new Outcome(Code.RETRY, message);
        }
    }

    /** 按事实主键推进；抛出异常表示可重试的瞬时失败（由 Worker 重放）。 */
    Outcome apply(Long factId);
}
