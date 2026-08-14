package com.jbk.tool.config.system.redis.consts;

import cn.hutool.core.util.StrUtil;

public interface RedisKeys {
    // 系统统一前缀
    String UNIFY_PRE = "auth-ai:";
    String UNIFY_PRE_LOCK = "auth-ai:lock:";
    // 认证标识
    String SYS_TOKEN = "auto-ai-token";

    String ALL = "ALL";

    String DELAY_QUEUE = UNIFY_PRE + "redis-delay:queue";

    // Comm ====================================================================================================
    class Comm {
        // 字典
        public static final String JET_DICT_TYPE = UNIFY_PRE + "dict";

        // 重复提交
        public static String getRepeatSubmit(String url, Long user, int hash) {
            return StrUtil.format(UNIFY_PRE + "url-{}:user-{}-hash-{}", url, user, hash);
        }

        // 权限
        public static final String RBAC = UNIFY_PRE + "rbac";


        // 异常
        public static final String EXCEPTION_IP_CNT = UNIFY_PRE + "exception:ip:cnt";
        public static final String EXCEPTION_IP_TEMP = UNIFY_PRE + "exception:ip:temp";
        public static final String EXCEPTION_IP_ALL = UNIFY_PRE + "exception:ip:all";
        public static final String EXCEPTION_IP_TEMP_ALL = UNIFY_PRE + "exception:ip:temp:all";
    }

    class Wechat {
        public static final String WECHAT_XCX_ACCESS_TOKEN = UNIFY_PRE + "wechat:xcx:access-token";
        public static final String WECHAT_FWH_ACCESS_TOKEN = UNIFY_PRE + "wechat:fwh:access-token";
    }

    /** 小程序 L2-AUTH：一次性绑定票据键（GETDEL 原子领取，成功/失败/过期均不可重放）。 */
    class MiniAuth {
        private static final String BIND_TICKET_PRE = UNIFY_PRE + "mini:auth:bind:";

        public static String bindTicket(String ticket) {
            return BIND_TICKET_PRE + ticket;
        }
    }

    class WsUser {
        public static final String LOCK_CHECK = UNIFY_PRE_LOCK + "ws-user:check";
        public static final String LOCK_USER = UNIFY_PRE_LOCK + "ws-user";

        public static String getImportAuthExcel(Long userId) {
            return UNIFY_PRE + "import:auth:" + userId;
        }

        public static String getCheckNo(String day) {
            return StrUtil.format(UNIFY_PRE + "check:no:{}", day);
        }
    }

    class Geo {
        public static final String AREA_GEO = UNIFY_PRE + "area:geo";
        public static final String REST_GEO = UNIFY_PRE + "rest:geo";

        public static String userLocation(Long userId) {
            return StrUtil.format(UNIFY_PRE + "geo:user:{}", userId);
        }

        public static String employeeLocation(Long employee) {
            return StrUtil.format(UNIFY_PRE + "geo:employee:{}", employee);
        }

        public static String areaGo(Long userId, Long areaId) {
            return StrUtil.format(UNIFY_PRE + "area:go:user:{}-{}", userId, areaId);
        }
    }

    class Task {
        public static final String LOCK_TASK = UNIFY_PRE_LOCK + "task";
    }
}


