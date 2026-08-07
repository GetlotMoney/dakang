package com.jbk.tool.utils.satoken;

/**
 * @ClassName StpKit
 * @Author xs
 * @Date 2024/6/11 10:45
 * @Version 1.0
 */

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpLogic;
import com.google.common.collect.Maps;
import com.jbk.tool.exception.ErrorMsg;
import com.jbk.tool.exception.JbkException;

import java.util.Map;

/**
 * StpLogic 门面类，管理项目中所有的 StpLogic 账号体系
 */
public class StpKit {

    private static final Map<String, StpLogic> map = Maps.newHashMap();

    public static final String EXTRA_NAME = "name";

    /**
     * 后台会话 JWT extra：是否需先修改初始密码（R-201）。
     * 登录时随 token 签发；改密与重置密码都会强制下线，重新登录后标记自然刷新，
     * 因此读取方（PwdChangeGuardInterceptor）只信 token 内标记、不回查数据库。
     */
    public static final String EXTRA_PWD_CHANGE = "pwdChange";


    /**
     * 后台会话对象
     */
    public static final String DRIVER_KH_USER = "kh_user";
    public static final StpLogic KH_USER = new StpLogicJwtForSimple("kh_user");
    public static final String DRIVER_MANAGE = "manage";
    public static final StpLogic MANAGE = new StpLogicJwtForSimple("manage");

    static {
        map.put(DRIVER_MANAGE, MANAGE);
        map.put(DRIVER_KH_USER, KH_USER);
    }

    public static StpLogic getStpByDevice(String device) {
        switch (device) {
            case DRIVER_MANAGE:
                return MANAGE;
            case DRIVER_KH_USER:
                return KH_USER;
            default:
                throw new JbkException(ErrorMsg.ERROR);
        }
    }

    public static UserTypeEnum getUserType(StpLogic stpLogic) {
        String loginType = stpLogic.getLoginType();
        UserTypeEnum type = UserTypeEnum.getType(loginType);
        return type;
    }


    public static StpLogic getStp() {
        for (StpLogic stpLogic : map.values()) {
            if (stpLogic.isLogin()) {
                return stpLogic;
            }
        }
        for (StpLogic stpLogic : map.values()) {
            try {
                stpLogic.checkLogin();
            } catch (NotLoginException e) {
                if (
                        e.getType().equals(NotLoginException.TOKEN_TIMEOUT) ||
                                e.getType().equals(NotLoginException.BE_REPLACED) ||
                                e.getType().equals(NotLoginException.KICK_OUT) ||
                                e.getType().equals(NotLoginException.TOKEN_FREEZE)
                ) {
                    throw e;
                }
            }
        }
        throw new JbkException(ErrorMsg.TOKEN_AUTH_FAIL);
    }


}



