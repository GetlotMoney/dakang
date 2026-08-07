package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.serve.service.mini.auth.BoundUser;
import com.jbk.serve.service.mini.auth.IMiniAuthBindTx;
import com.jbk.serve.service.mini.auth.MiniUserIdentitySupport;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * L2-AUTH 绑定的独立事务实现（复审 P0-2）。
 *
 * <p>所有身份读+写都在本方法的单个事务内完成；方法返回即代表事务已提交，编排层随后才签发 Token。
 * 已有手机号绑定 openid 使用条件 UPDATE（影响行数必须为 1），并发下仅一个成功，其余 fail-closed。</p>
 */
@Service
@RequiredArgsConstructor
public class MiniAuthBindTxImpl implements IMiniAuthBindTx {

    private static final int POINTS_INIT = 0;
    private static final long SYSTEM_ACTOR = 1L;
    /** 无手机号时的昵称占位；建号后补 ID 尾号，改名守卫也认这个值。 */
    private static final String PLACEHOLDER_USER_NAME = "微信用户";
    private static final int ID_SUFFIX_LENGTH = 4;

    private final WsUserIdentityMapper identityMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BoundUser bind(String openid, String phone) {
        // 1) openid 已存在（跨全部 DATA_STATUS）：幂等或冲突，绝不再建户。
        WsUser byOpenid = MiniUserIdentitySupport.resolveUnique(
                identityMapper.selectByOpenidIncludingDeleted(openid),
                "该微信账号身份数据异常，请联系客服");
        if (byOpenid != null) {
            MiniUserIdentitySupport.assertUsable(byOpenid);
            if (!StrUtil.equals(phone, byOpenid.getUserPhone())) {
                throw new JbkException("该微信已绑定其他手机号，无法重复绑定");
            }
            return new BoundUser(byOpenid.getId(), byOpenid.getUserName(), byOpenid.getUserPhone());
        }

        // 2) 手机号已存在（跨全部 DATA_STATUS）：有效性 fail-closed + 条件 UPDATE 抢绑。
        WsUser byPhone = MiniUserIdentitySupport.resolveUnique(
                identityMapper.selectByPhoneIncludingDeleted(phone),
                "该手机号身份数据异常，请联系客服");
        if (byPhone != null) {
            MiniUserIdentitySupport.assertUsable(byPhone);
            if (StrUtil.equals(openid, byPhone.getWechatXcxOpenid())) {
                // 幂等：手机号已绑定同一 openid（理论上前一步已命中，防御性保留）。
                return new BoundUser(byPhone.getId(), byPhone.getUserName(), byPhone.getUserPhone());
            }
            if (StrUtil.isNotBlank(byPhone.getWechatXcxOpenid())) {
                throw new JbkException("该手机号已绑定其他微信账号");
            }
            int affected;
            try {
                affected = identityMapper.bindOpenidToUsablePhoneUser(
                        byPhone.getId(), phone, openid, SYSTEM_ACTOR, DateUtils.time());
            } catch (DuplicateKeyException e) {
                throw new JbkException("绑定冲突，请重新登录后再试");
            }
            if (affected != 1) {
                // 并发另一 openid 已抢绑、或状态在读后被改：影响 0 行，一律 fail-closed。
                throw new JbkException("绑定冲突，请重新登录后再试");
            }
            return new BoundUser(byPhone.getId(), byPhone.getUserName(), phone);
        }

        // 3) 手机号从未注册：建最小用户（唯一约束兜底并发）。
        WsUser created = buildMinimalUser(phone, openid, DateUtils.time());
        try {
            int rows = identityMapper.insertIdentityUser(created);
            if (rows != 1 || created.getId() == null) {
                throw new JbkException("建户失败，请重新登录后再试");
            }
        } catch (DuplicateKeyException e) {
            throw new JbkException("绑定冲突，请重新登录后再试");
        }
        // 首次建号成功（D-418 注册送触发条件）：newlyCreated 只在这条真实 insert 成功路径为 true
        return new BoundUser(created.getId(), created.getUserName(), phone, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BoundUser registerByOpenid(String openid) {
        // 1) openid 已存在（跨全部 DATA_STATUS）：幂等返回，绝不重复建号。
        WsUser existing = MiniUserIdentitySupport.resolveUnique(
                identityMapper.selectByOpenidIncludingDeleted(openid),
                "该微信账号身份数据异常，请联系客服");
        if (existing != null) {
            MiniUserIdentitySupport.assertUsable(existing);
            return new BoundUser(existing.getId(), existing.getUserName(), existing.getUserPhone());
        }

        // 2) 建最小用户，手机号留 NULL（不是空串——空串之间会互撞 uk_user_phone，第二个未绑用户就建不出来）。
        String now = DateUtils.time();
        WsUser created = buildMinimalUser(null, openid, now);
        try {
            int rows = identityMapper.insertIdentityUser(created);
            if (rows != 1 || created.getId() == null) {
                throw new JbkException("建号失败，请重新登录后再试");
            }
        } catch (DuplicateKeyException e) {
            // 同一 openid 并发登录（用户连点、断网重试）撞 uk_user_wechat_xcx_openid：这是正常竞态不是冲突，
            // 重读胜出行返回即可。必须用锁定读——RR 下普通 SELECT 仍是本事务开头的旧快照，看不见对方刚提交的行。
            WsUser winner = MiniUserIdentitySupport.resolveUnique(
                    identityMapper.selectByOpenidForUpdate(openid),
                    "该微信账号身份数据异常，请联系客服");
            if (winner == null) {
                // 撞的不是 openid 唯一键（如 OWN_INVITE_CODE），不猜原因，fail-closed。
                throw new JbkException("建号冲突，请重新登录后再试");
            }
            MiniUserIdentitySupport.assertUsable(winner);
            return new BoundUser(winner.getId(), winner.getUserName(), winner.getUserPhone());
        }
        // 改名放在 try 之外：上面那个 catch 只为兜 insert 撞 openid 唯一键这一种情况，
        // 把无关写入圈进去会让「撞的是哪个键」变得不可判定。
        created.setUserName(applyIdSuffixedName(created.getId(), now));
        // 跟着刚写入的对象取号码，不硬编码 null：将来若建号逻辑改成带号，这里不会漏改。
        // 首次建号成功（D-418 注册送触发条件）；并发撞键的重读路径返回三参形态=false，不双发
        return new BoundUser(created.getId(), created.getUserName(), created.getUserPhone(), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BoundUser bindPhoneToCurrentUser(Long userId, String phone) {
        // 1) 手机号已归属他人：一律 fail-closed。两个账号各自带着卡、订单、余额，合并不是登录链路能承担的动作。
        WsUser byPhone = MiniUserIdentitySupport.resolveUnique(
                identityMapper.selectByPhoneIncludingDeleted(phone),
                "该手机号身份数据异常，请联系客服");
        if (byPhone != null) {
            if (ObjectUtil.equals(byPhone.getId(), userId)) {
                // 幂等：本人已绑同一号码，重复提交直接返回。
                MiniUserIdentitySupport.assertUsable(byPhone);
                return new BoundUser(byPhone.getId(), byPhone.getUserName(), byPhone.getUserPhone());
            }
            throw new JbkException("该手机号已被其他账号使用，请联系客服处理");
        }

        // 2) CAS 补绑：只允许 NULL → 非空。已绑号则影响 0 行（换绑需另走身份复核，不从此路进）。
        // UPDATE_BY 记本人而非 SYSTEM_ACTOR：补绑是用户自助行为，记成系统操作会让审计查不出是谁绑的。
        int affected;
        try {
            affected = identityMapper.bindPhoneToPhonelessUser(userId, phone, userId, DateUtils.time());
        } catch (DuplicateKeyException e) {
            // 上一步读到无主、写入前被并发抢注：唯一键兜底。
            throw new JbkException("该手机号已被其他账号使用，请联系客服处理");
        }
        if (affected != 1) {
            // 影响 0 行有两种成因，用户该做的事完全相反：已绑号是前端上下文陈旧（刷新即可），
            // 状态异常则只能找客服。糊成一句「无法补绑」等于把用户留在死胡同里反复点。
            WsUser current = identityMapper.selectByIdIncludingDeleted(userId);
            if (current != null && StrUtil.isNotBlank(current.getUserPhone())) {
                throw new JbkException("该账号已绑定手机号，请退出后重新进入小程序刷新");
            }
            throw new JbkException("账号状态异常，无法补绑，请联系客服");
        }

        WsUser bound = MiniUserIdentitySupport.resolveUnique(
                identityMapper.selectByPhoneIncludingDeleted(phone),
                "该手机号身份数据异常，请联系客服");
        if (bound == null) {
            throw new JbkException("补绑失败，请稍后重试");
        }
        return new BoundUser(bound.getId(), bound.getUserName(), bound.getUserPhone());
    }

    /** 最小用户主体：gender=NULL、disabled=1、status=1、points=0，使用系统主体与调用方传入的同一时刻。 */
    private WsUser buildMinimalUser(String phone, String openid, String now) {
        WsUser user = new WsUser();
        user.setUserName(defaultUserName(phone));
        user.setUserGender(null);
        user.setUserPhone(phone);
        user.setWechatXcxOpenid(openid);
        user.setDisabledFlag(MiniUserIdentitySupport.DISABLED_FLAG_ENABLED);
        user.setUserStatus(MiniUserIdentitySupport.USER_STATUS_ACTIVE);
        user.setPoints(POINTS_INIT);
        user.setDataStatus(MiniUserIdentitySupport.DATA_STATUS_NORMAL);
        user.setCreateBy(SYSTEM_ACTOR);
        user.setCreateTime(now);
        user.setUpdateBy(SYSTEM_ACTOR);
        user.setUpdateTime(now);
        return user;
    }

    /** 默认昵称：手机号脱敏（USER_NAME 上限 50）。无手机号时是占位值，建号后由 ID 尾号补充辨识度。 */
    private String defaultUserName(String phone) {
        if (StrUtil.length(phone) == 11) {
            return "用户" + phone.substring(7);
        }
        return PLACEHOLDER_USER_NAME;
    }

    /**
     * 建号后把占位昵称补成「微信用户+ID 尾号」。
     *
     * <p>不做的话，仅微信身份建的号全叫「微信用户」，且它们恰恰都没有手机号——PC 用户列表里
     * 一排同名无号账号，运营要给其中某人开通配送员/机主时点不准是谁。开错的后果不是难看：
     * 配送任务带着收货地址与联系电话，权限给错人就是把用户住址泄露给了无关的人。</p>
     *
     * <p>回填失败（昵称已被改过、行不存在）不阻断登录：辨识度是运营便利，不是建号的前置条件。</p>
     */
    private String applyIdSuffixedName(Long id, String now) {
        String named = PLACEHOLDER_USER_NAME + idSuffix(id);
        int renamed = identityMapper.renamePlaceholderUserName(
                id, PLACEHOLDER_USER_NAME, named, SYSTEM_ACTOR, now);
        return renamed == 1 ? named : PLACEHOLDER_USER_NAME;
    }

    /** ID 尾号（不足 4 位取全量）：与 PC 用户列表的「用户ID」列可直接对上。 */
    private static String idSuffix(Long id) {
        String text = String.valueOf(id);
        return text.length() <= ID_SUFFIX_LENGTH ? text : text.substring(text.length() - ID_SUFFIX_LENGTH);
    }
}
