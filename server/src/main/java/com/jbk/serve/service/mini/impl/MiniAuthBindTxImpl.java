package com.jbk.serve.service.mini.impl;

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
        WsUser created = buildMinimalUser(phone, openid);
        try {
            int rows = identityMapper.insertIdentityUser(created);
            if (rows != 1 || created.getId() == null) {
                throw new JbkException("建户失败，请重新登录后再试");
            }
        } catch (DuplicateKeyException e) {
            throw new JbkException("绑定冲突，请重新登录后再试");
        }
        return new BoundUser(created.getId(), created.getUserName(), phone);
    }

    /** 最小用户主体：gender=NULL、disabled=1、status=1、points=0，使用系统主体与服务端时间。 */
    private WsUser buildMinimalUser(String phone, String openid) {
        WsUser user = new WsUser();
        String now = DateUtils.time();
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

    /** 默认昵称：手机号脱敏（USER_NAME 上限 50）。 */
    private String defaultUserName(String phone) {
        if (StrUtil.length(phone) == 11) {
            return "用户" + phone.substring(7);
        }
        return "微信用户";
    }
}
