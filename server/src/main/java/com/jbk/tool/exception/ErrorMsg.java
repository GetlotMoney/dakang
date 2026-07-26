package com.jbk.tool.exception;

/**
 * 系统接口 异常Code：1000+
 * 功能模块 异常Code：10000+ ：每一个功能模块code +20 00:系统表示，000：业务标识
 * auth:10
 */
public enum ErrorMsg {
    SUCCESS(0, "成功"),
    ERROR(500, "服务端发生异常，请联系管理员"),
    ERROR_CUSTOM(540, "服务器出现错误，请联系管理员"),
    PARAMETER_ERROR(550, "参数不符合规范"),
    UPLOAD_FILE_ERROR(600, "文件上传失败"),
    DELETE_FILE_ERROR(601, "文件删除失败"),
    BUSY_WORK(620, "业务繁忙，请稍后再试"),
    NETWORK_ERROR(621, "网络通信异常，请联系管理员"),
    RATE_LIMIT_ERROR(622, "系统当前访问量较大，请稍后尝试"),
    REPEAT_SUBMIT(625, "重复提交，请稍后尝试"),
    HTTP_TIME_OUT(700, "请求超时"),
    UA_UNKNOWN(800, "账户操作平台错误"),

    TOKEN_AUTH_FAIL(1401, "登录状态异常"),// 请重新进入小程序
    TOKEN_AUTH_DEATH(1402, "登录超时，请重新登录"),// 登录超时，请重新登录
    TOKEN_AUTH_REPLACE(1403, "当前账户被顶下线"),// 当前账户被顶下线
    TOKEN_AUTH_KICK(1404, "当前账户被踢下线"),// 当前账户被踢下线
    TOKEN_AUTH_FREEZE(1405, "账户长时间未操作已被冻结，请重新登录"),// 账户长时间未操作已被冻结，请重新登录

    AUTH_ERROR(1410, "AUTH认证失败"),
    AUTH_LOGIN_NOT_EXIST(1411, "登录账号不存在，请检查"),
    AUTH_PASSWORD_NO_MATCH(1412, "账号密码不匹配，请检查"),

    PERMISSION_FAIL(1420, "权限不足"),
    ROLE_FAIL(1430, "角色不足"),
    SAFE_FAIL(1440, "二级认证失败"),

    DICT_GET_ERROR(1560, "字典获取异常"),
    DICT_PARSE_ERROR(1561, "字典解析异常"),
    DICT_EXIST(1562, "字典已存在"),
    SQL_INJECT_ERROR(1580, "参数存在SQL注入风险"),
    SQL_NORM_ERROR(1581, "参数不符合规范，不能进行查询"),

    INFO_EXIST(2000, "当前请求资源不存在"),

    DATA_BASE_ERROR(2100, "不支持的数据库操作"),

    TEST(11111111, "占位");

    /**
     * 错误码
     */
    private int code;
    /**
     * 错误提示
     */
    private String msg;

    ErrorMsg(int code, String msg) {
        this.code = code;
        this.msg = msg;
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
}


