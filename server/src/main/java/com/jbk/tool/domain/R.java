package com.jbk.tool.domain;

import com.jbk.tool.exception.ErrorMsg;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @ClassName R
 * @Author xs
 * @Date 2024/6/7 14:19
 * @Version 1.0
 */
public class R<T> {
    @Schema(description = "响应数据")
    private T data;
    @Schema(description = "消息内容")
    private String msg = ErrorMsg.SUCCESS.getMsg();
    @Schema(description = "编码：0表示成功，其他值表示失败")
    private Integer code = ErrorMsg.SUCCESS.getCode();

    public static <T> R<T> ok(T data) {
        R<T> res = new R<>();
        res.setCode(ErrorMsg.SUCCESS.getCode());
        res.setData(data);
        return res;
    }

    public static <T> R<T> ok(T data, String msg) {
        R<T> res = new R<>();
        res.setCode(ErrorMsg.SUCCESS.getCode());
        res.setData(data);
        res.setMsg(msg);
        return res;
    }

    public static <T> R<T> error() {
        R<T> res = new R<>();
        res.setCode(ErrorMsg.ERROR.getCode());
        res.setMsg(ErrorMsg.ERROR.getMsg());
        return res;
    }

    public static <T> R<T> error(ErrorMsg msg) {
        R<T> res = new R<>();
        res.setCode(msg.getCode());
        res.setMsg(msg.getMsg());
        return res;
    }

    public static <T> R<T> error(String msg,int code) {
        R<T> res = new R<>();
        res.setCode(code);
        res.setMsg(msg);
        return res;
    }
    public static <T> R<T> error(String msg) {
        R<T> res = new R<>();
        res.setCode(ErrorMsg.ERROR_CUSTOM.getCode());
        res.setMsg(msg);
        return res;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}


