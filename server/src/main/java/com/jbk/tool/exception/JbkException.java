package com.jbk.tool.exception;

public class JbkException extends RuntimeException {
    private String msg;
    private int code;

    /**
     * 该异常的 {@link #msg} 是否适合直接展示给用户。
     *
     * <p>GlobalExceptionHandler 把 msg 原样写进响应体，前端直接 toast。对「余额不足」
     * 这类业务规则拒绝，原文就是用户要看的；但对不变式守卫（共键错位、前态漂移、
     * 事实落库失败）原文是给排障看的诊断，弹给用户只会造成恐慌且毫无可操作性。</p>
     *
     * <p>默认 true 保持既有行为不变；诊断类异常用 {@link #internal(String)} 构造，
     * 由 handler 替换成通用文案——精确原因仍完整落在 log.error 里，不丢排障信息。</p>
     */
    private boolean userFacing = true;

    /** 面向用户展示的兜底文案：不暴露内部状态，但明确「重试或找人」两条出路。 */
    public static final String INTERNAL_FALLBACK_MSG = "操作未能完成，请稍后重试；若反复出现请联系客服";

    /**
     * 构造一个<b>不直接展示给用户</b>的诊断异常。
     *
     * @param diagnostic 精确原因，会完整进入日志，但不会出现在响应体里
     */
    public static JbkException internal(String diagnostic) {
        JbkException e = new JbkException(diagnostic);
        e.userFacing = false;
        return e;
    }

    public boolean isUserFacing() {
        return userFacing;
    }

    public String getMsg() {
        return msg;
    }

    public int getCode() {
        return code;
    }

    public JbkException(ErrorMsg errorMsg) {
        super(errorMsg.getMsg());
        this.msg = errorMsg.getMsg();
        this.code = errorMsg.getCode();
    }

    public JbkException(String msg) {
        super(msg);
        this.code = ErrorMsg.ERROR_CUSTOM.getCode();
        this.msg = msg;
    }

    public JbkException(String msg,int code) {
        super(msg);
        this.code = code;
        this.msg = msg;
    }
}


