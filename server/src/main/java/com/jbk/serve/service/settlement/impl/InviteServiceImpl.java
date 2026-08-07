package com.jbk.serve.service.settlement.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.serve.service.settlement.IInviteService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.OptionalUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 邀请归因实现。
 *
 * <p>邀请码派生沿用全仓编号先例（确定性 SHA-256、无日期无序列无随机）：
 * IV + SHA256("INVITE:" + userId [+ ":盐序"]) 十六进制前 8 位大写——8 位十六进制
 * 约 42 亿空间，demo 量级冲突概率可忽略，真冲突由 uk_user_invite_code 兜底加盐重派生。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
@Slf4j
@Service
public class InviteServiceImpl implements IInviteService {

    private static final int SALT_RETRY = 3;

    @Autowired
    private WsUserMapper userMapper;
    @Autowired
    private IWsDomainEventService domainEventService;

    @Override
    public String myInviteCode(Long userId) {
        WsUser user = userMapper.selectById(userId);
        OptionalUtils.nullToElseThrow(user, "用户不存在");
        if (StrUtil.isNotBlank(user.getOwnInviteCode())) {
            return user.getOwnInviteCode();
        }
        for (int salt = 0; salt < SALT_RETRY; salt++) {
            String code = deriveCode(userId, salt);
            int updated;
            try {
                // 前态 CAS：码列仍为空才写——并发双请求只有一方落码，输方读回胜者结果
                updated = userMapper.update(null, Wrappers.lambdaUpdate(WsUser.class)
                        .eq(WsUser::getId, userId)
                        .isNull(WsUser::getOwnInviteCode)
                        .set(WsUser::getOwnInviteCode, code));
            }
            catch (DuplicateKeyException e) {
                // uk_user_invite_code 撞库=派生值与他人既有码碰撞（8 位十六进制空间的真冲突）：
                // 不 catch 会确定性重抛，该用户永远拿不到码——加盐重派生才是活路
                log.info("邀请码派生碰撞，加盐重试：userId={} salt={}", userId, salt);
                continue;
            }
            if (updated == 1) {
                return code;
            }
            WsUser fresh = userMapper.selectById(userId);
            if (StrUtil.isNotBlank(fresh.getOwnInviteCode())) {
                return fresh.getOwnInviteCode();
            }
        }
        throw new JbkException("邀请码生成冲突，请重试");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindReferrer(Long userId, String inviteCode) {
        if (StrUtil.isBlank(inviteCode)) {
            throw new JbkException("邀请码不能为空");
        }
        WsUser referrer = userMapper.selectOne(Wrappers.lambdaQuery(WsUser.class)
                .eq(WsUser::getOwnInviteCode, inviteCode.trim().toUpperCase()));
        if (ObjectUtil.isNull(referrer)) {
            throw new JbkException("邀请码不存在");
        }
        if (ObjectUtil.equal(referrer.getId(), userId)) {
            throw new JbkException("不能填写自己的邀请码");
        }
        // 一次性绑定：REFERRER_USER_ID IS NULL 前态 CAS——已绑定明确拒绝（不静默换绑，
        // 换绑/申诉属 REQ-058 商业一期流程，本期只留字段骨架）
        int updated = userMapper.update(null, Wrappers.lambdaUpdate(WsUser.class)
                .eq(WsUser::getId, userId)
                .isNull(WsUser::getReferrerUserId)
                .set(WsUser::getReferrerUserId, referrer.getId())
                .set(WsUser::getPromoCode, inviteCode.trim().toUpperCase()));
        if (updated != 1) {
            throw new JbkException("已绑定过推荐人，不可重复绑定");
        }
        // 归因绑定是分润事实链的起点：关键审计，与绑定同事务同灭
        domainEventService.recordReliableOnce(OpsEnum.EventType.ORDER_STATUS, "USER:" + userId,
                "INVITE_BIND:" + userId, null, "绑定推荐人 " + referrer.getId());
    }

    @Override
    public Long referrerSnapshotOf(Long userId) {
        WsUser user = userMapper.selectById(userId);
        return ObjectUtil.isNull(user) ? null : user.getReferrerUserId();
    }

    private String deriveCode(Long userId, int salt) {
        String seed = salt == 0 ? "INVITE:" + userId : "INVITE:" + userId + ":" + salt;
        return "IV" + DigestUtil.sha256Hex(seed).substring(0, 8).toUpperCase();
    }
}
