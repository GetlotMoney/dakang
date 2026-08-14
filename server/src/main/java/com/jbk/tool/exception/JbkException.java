package com.jbk.tool.exception;

public class JbkException extends RuntimeException {
    private String msg;
    private int code;

    /**
     * 该异常的 {@link #msg} 是否适合直接展示给用户。业务规则拒绝原文即用户文案；
     * 不变式守卫类诊断用 {@link #internal(String)} 构造，handler 替换成通用文案，精确原因仍完整进 log.error。
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


