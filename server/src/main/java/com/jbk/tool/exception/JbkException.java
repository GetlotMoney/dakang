package com.jbk.tool.exception;

public class JbkException extends RuntimeException {
    private String msg;
    private int code;


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


