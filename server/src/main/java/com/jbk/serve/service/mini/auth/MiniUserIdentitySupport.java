package com.jbk.serve.service.mini.auth;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;

import java.util.List;

/**
 * L2-AUTH 身份判定共用规则：唯一性收敛与「有效用户」断言。登录（只读）与绑定事务 Bean 复用同一套判定。
 */
public final class MiniUserIdentitySupport {

    /** 按统一身份规则判定用户是否可用。 */
    public static final int DATA_STATUS_NORMAL = 0;
    public static final int DISABLED_FLAG_ENABLED = 1;
    public static final int USER_STATUS_ACTIVE = 1;

    private MiniUserIdentitySupport() {
    }

    /**
     * 身份唯一性收敛：跨全部 DATA_STATUS 的查询结果必须为 0 或 1 条。
     * &gt;1 条即身份污染（迁移前遗留重复），一律 fail-closed 拒绝，绝不猜测归属。
     */
    public static WsUser resolveUnique(List<WsUser> rows, String pollutionMessage) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (rows.size() > 1) {
            throw new JbkException(pollutionMessage);
        }
        return rows.get(0);
    }

    /**
     * 账号可用性的<b>唯一判据</b>：三字段全部正常才算可用（null 视为不可用）。
     * 无副作用，供不能抛异常的旁路（如订阅通知 Worker）复用——判据只能有这一份。
     */
    public static boolean isUsable(WsUser user) {
        return ObjectUtil.isNotNull(user)
                && ObjectUtil.equals(user.getDataStatus(), DATA_STATUS_NORMAL)
                && ObjectUtil.equals(user.getDisabledFlag(), DISABLED_FLAG_ENABLED)
                && ObjectUtil.equals(user.getUserStatus(), USER_STATUS_ACTIVE);
    }

    /** 有效用户：DATA_STATUS=0 且 DISABLED_FLAG=1 且 USER_STATUS=1；删除/禁用/注销一律 fail-closed。 */
    public static void assertUsable(WsUser user) {
        if (!isUsable(user)) {
            throw new JbkException("账号不可用（已禁用、注销或删除），请联系客服");
        }
    }
}
