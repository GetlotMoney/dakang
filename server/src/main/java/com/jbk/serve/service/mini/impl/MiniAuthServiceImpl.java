package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.serve.service.mini.IMiniAuthService;
import com.jbk.serve.service.mini.IMiniCapabilityService;
import com.jbk.serve.service.mini.auth.BoundUser;
import com.jbk.serve.service.mini.auth.IKhUserSessionIssuer;
import com.jbk.serve.service.mini.auth.IMiniAuthBindTx;
import com.jbk.serve.service.mini.auth.IWechatCode2SessionAdapter;
import com.jbk.serve.service.mini.auth.IWechatPhoneAdapter;
import com.jbk.serve.service.mini.auth.KhUserSession;
import com.jbk.serve.service.mini.auth.MiniUserIdentitySupport;
import com.jbk.serve.service.mini.auth.WechatCode2SessionResult;
import com.jbk.tool.config.system.redis.consts.RedisExpire;
import com.jbk.tool.config.system.redis.consts.RedisKeys;
import com.jbk.tool.config.system.redis.utils.RedisUtils;
import com.jbk.tool.config.wechat.WechatXcxConfig;
import com.jbk.tool.data.mini.bo.MiniBindPhoneBo;
import com.jbk.tool.data.mini.bo.MiniLoginBo;
import com.jbk.tool.data.mini.vo.MiniAccountContextVo;
import com.jbk.tool.data.mini.vo.MiniAuthResultVo;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.security.SecureRandom;
import java.util.List;

/**
 * 小程序 L2-AUTH 编排层：微信登录 + 手机号绑定，全程 fail-closed。
 *
 * <p>身份读取一律走 {@link WsUserIdentityMapper}（跨全部 DATA_STATUS，避免 {@code @TableLogic} 隐藏删除账号，复审 P1-1）；
 * 绑定写入委托 {@link IMiniAuthBindTx} 独立事务，事务提交成功后本层才签发 Token（复审 P0-2）。</p>
 *
 * @author dakang
 * @since 2026-07-21
 */
@Service
@RequiredArgsConstructor
public class MiniAuthServiceImpl implements IMiniAuthService {

    private static final String TICKET_PURPOSE_BIND_PHONE = "bind-phone";
    private static final long BIND_TICKET_TTL_SECONDS = RedisExpire.FIVE_EXPIRE; // 300s

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final WsUserIdentityMapper identityMapper;
    private final IWechatCode2SessionAdapter code2SessionAdapter;
    private final IWechatPhoneAdapter phoneAdapter;
    private final IKhUserSessionIssuer sessionIssuer;
    private final IMiniAuthBindTx bindTx;
    private final WechatXcxConfig wechatXcxConfig;
    private final IMiniCapabilityService capabilityService;

    @Resource(name = "redisTemplate1")
    private RedisTemplate<String, Object> redis;

    @Override
    public MiniAuthResultVo login(MiniLoginBo bo) {
        WechatCode2SessionResult session = code2SessionAdapter.resolve(bo.getCode());
        String openid = session.openid();

        WsUser user = findUsableByOpenid(openid);
        if (ObjectUtil.isNull(user)) {
            // 未绑定：下发一次性 bindTicket（128bit 随机、绑 appid/openid/purpose/签发时间，TTL 5min）。
            String ticket = issueBindTicket(openid);
            return MiniAuthResultVo.unbound(ticket, BIND_TICKET_TTL_SECONDS);
        }
        return boundResult(user.getId(), user.getUserName(), user.getUserPhone());
    }

    @Override
    public MiniAuthResultVo bindPhone(MiniBindPhoneBo bo) {
        // 1) 原子领取 bindTicket（GETDEL）：并发/重复/过期均只有一个能取到，取到即销毁，不可重放。
        Object raw = RedisUtils.getDel(redis, RedisKeys.MiniAuth.bindTicket(bo.getBindTicket()));
        if (ObjectUtil.isNull(raw)) {
            throw new JbkException("绑定票据无效或已过期，请重新登录");
        }
        JSONObject payload = JSON.parseObject(String.valueOf(raw));
        String openid = payload.getString("openid");
        String purpose = payload.getString("purpose");
        String appid = payload.getString("appid");
        // 2) 票据用途/来源核验（purpose 错位、appid 不符、openid 缺失一律拒绝）。
        if (!TICKET_PURPOSE_BIND_PHONE.equals(purpose)
                || StrUtil.isBlank(openid)
                || !StrUtil.equals(appid, currentAppid())) {
            throw new JbkException("绑定票据非法，请重新登录");
        }

        // 3) 服务端解析手机号（不信前端直传）。
        String phone = phoneAdapter.resolvePhone(bo.getPhoneCode());

        // 4) 数据库绑定在独立事务内完成；返回即代表已提交，随后才签发 Token（提交失败则抛异常、绝不签发）。
        BoundUser bound = bindTx.bind(openid, phone);

        // 5) 事务提交成功后签发 KH_USER 会话。
        return boundResult(bound.id(), bound.userName(), bound.userPhone());
    }

    // ---------------------------------------------------------------------

    /**
     * 跨全部 DATA_STATUS 精确匹配 openid，唯一性收敛（0/1/污染拒），命中即断言有效性。
     * 已删除/禁用/注销账号在此被 fail-closed 拒绝，绝不误判为 UNBOUND 进入建户路径。
     */
    private WsUser findUsableByOpenid(String openid) {
        if (StrUtil.isBlank(openid)) {
            return null;
        }
        List<WsUser> rows = identityMapper.selectByOpenidIncludingDeleted(openid);
        WsUser user = MiniUserIdentitySupport.resolveUnique(rows, "该微信账号身份数据异常，请联系客服");
        if (ObjectUtil.isNull(user)) {
            return null;
        }
        MiniUserIdentitySupport.assertUsable(user);
        return user;
    }

    /** 建立 KH_USER 会话并组装不含身份密钥的 BOUND 结果。 */
    private MiniAuthResultVo boundResult(Long userId, String userName, String userPhone) {
        KhUserSession token = sessionIssuer.issue(userId, userName);
        MiniAccountContextVo ctx = new MiniAccountContextVo();
        ctx.setAccountId(String.valueOf(userId));
        ctx.setUserId(String.valueOf(userId));
        ctx.setUserName(userName);
        ctx.setUserPhone(userPhone);
        // E2E-03 包B：配送能力投影接真（USER_BASE/COURIER_APPLY 基础 + 启用配送员 COURIER_WORK）；
        // 投影只改善导航不构成授权，配送接口数据范围仍由 CourierAccess 逐调用强制（铁律6/7）。
        ctx.setCapabilities(capabilityService.capabilitiesOf(userId));
        return MiniAuthResultVo.bound(token.tokenName(), token.tokenValue(), ctx);
    }

    private String issueBindTicket(String openid) {
        byte[] bytes = new byte[16]; // 128 bit
        SECURE_RANDOM.nextBytes(bytes);
        String ticket = toHex(bytes);
        JSONObject payload = new JSONObject();
        payload.put("appid", currentAppid());
        payload.put("openid", openid);
        payload.put("purpose", TICKET_PURPOSE_BIND_PHONE);
        payload.put("issuedAt", DateUtils.time());
        RedisUtils.set(redis, RedisKeys.MiniAuth.bindTicket(ticket), payload.toJSONString(), BIND_TICKET_TTL_SECONDS);
        return ticket;
    }

    private String currentAppid() {
        return StrUtil.blankToDefault(wechatXcxConfig.getAppid(), "");
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
