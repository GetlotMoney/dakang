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
import com.jbk.tool.data.mini.bo.MiniBindPhoneSelfBo;
import com.jbk.tool.data.mini.bo.MiniLoginBo;
import com.jbk.tool.data.mini.vo.MiniAccountContextVo;
import com.jbk.tool.data.mini.vo.MiniAuthResultVo;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 仅微信身份建号开关。
     *
     * <p>true：登录遇到新 openid 直接建号（手机号留空），手机号在小程序内自助补绑。
     * 小程序主体未通过微信认证时 {@code getPhoneNumber} 组件被平台禁用，这是唯一能走通的注册路径。
     * false（默认）：维持「必须先授权手机号才建号」，新 openid 返回 UNBOUND + bindTicket。</p>
     */
    @Value("${mini.auth.phoneless-register.enabled:false}")
    private boolean phonelessRegisterEnabled;

    private final WsUserIdentityMapper identityMapper;
    private final IWechatCode2SessionAdapter code2SessionAdapter;
    private final IWechatPhoneAdapter phoneAdapter;
    private final IKhUserSessionIssuer sessionIssuer;
    private final IMiniAuthBindTx bindTx;
    private final WechatXcxConfig wechatXcxConfig;
    private final IMiniCapabilityService capabilityService;
    private final com.jbk.serve.service.settlement.IRegisterGiftService registerGiftService;

    @Resource(name = "redisTemplate1")
    private RedisTemplate<String, Object> redis;

    @Override
    public MiniAuthResultVo login(MiniLoginBo bo) {
        WechatCode2SessionResult session = code2SessionAdapter.resolve(bo.getCode());
        String openid = session.openid();

        WsUser user = findUsableByOpenid(openid);
        if (ObjectUtil.isNull(user)) {
            if (phonelessRegisterEnabled) {
                // 仅微信身份建号：小程序主体未过微信认证时，getPhoneNumber 组件被平台禁用（点击不弹窗、
                // 回调不触发），拿不到 phoneCode，走原路径新用户永远停在 UNBOUND、整条登录链断死。
                // 此处以 openid 直接建号、手机号留空，登录后在「我的」自助补绑。
                BoundUser created = bindTx.registerByOpenid(openid);
                // D-418：注册建号事务已提交后触发注册送（幂等、失败不阻断登录）。
                // 并发同 openid 竞态里只有真正 insert 成功的那次 newlyCreated=true，不双发。
                if (created.newlyCreated()) {
                    registerGiftService.grantIfEnabled(created.id());
                }
                return boundResult(created.id(), created.userName(), created.userPhone());
            }
            // 未绑定：下发一次性 bindTicket（128bit 随机、绑 appid/openid/purpose/签发时间，TTL 5min）。
            String ticket = issueBindTicket(openid);
            return MiniAuthResultVo.unbound(ticket, BIND_TICKET_TTL_SECONDS);
        }
        return boundResult(user.getId(), user.getUserName(), user.getUserPhone());
    }

    @Override
    public MiniAccountContextVo bindPhoneForCurrentUser(Long userId, MiniBindPhoneSelfBo bo) {
        // 会话已建立，无需票据；手机号仍由服务端向微信换取，绝不信前端直传号码。
        String phone = phoneAdapter.resolvePhone(bo.getPhoneCode());
        BoundUser bound = bindTx.bindPhoneToCurrentUser(userId, phone);
        // 补绑不换会话：沿用当前 Token，只回吐刷新后的上下文，避免用户在成功后被动登出。
        return accountContextOf(bound.id(), bound.userName(), bound.userPhone());
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
        // D-418：绑定链里手机号首次建号同样是「注册成功」（另两分支复用既有账号，flag=false）
        if (bound.newlyCreated()) {
            registerGiftService.grantIfEnabled(bound.id());
        }

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
        return MiniAuthResultVo.bound(token.tokenName(), token.tokenValue(),
                accountContextOf(userId, userName, userPhone));
    }

    @Override
    public MiniAccountContextVo currentContext(Long userId) {
        WsUser user = identityMapper.selectByIdIncludingDeleted(userId);
        if (ObjectUtil.isNull(user)) {
            throw new JbkException("账号不存在，请重新登录");
        }
        MiniUserIdentitySupport.assertUsable(user);
        return accountContextOf(user.getId(), user.getUserName(), user.getUserPhone());
    }

    /** 组装账号上下文（不签发会话，补绑/资料更新后刷新也复用）。 */
    private MiniAccountContextVo accountContextOf(Long userId, String userName, String userPhone) {
        MiniAccountContextVo ctx = new MiniAccountContextVo();
        ctx.setAccountId(String.valueOf(userId));
        ctx.setUserId(String.valueOf(userId));
        ctx.setUserName(userName);
        ctx.setUserPhone(userPhone);
        // 头像按主键单独回查：登录/绑定各入口拿到的用户对象来源不一（BoundUser 不带头像），
        // 统一在装配点补齐，避免给每个入口的返回结构都加一个字段。PK 查询代价可忽略。
        WsUser row = identityMapper.selectByIdIncludingDeleted(userId);
        ctx.setUserAvatar(ObjectUtil.isNull(row) ? null : StrUtil.blankToDefault(row.getUserAvatar(), null));
        // 显式布尔而非「让前端判 userPhone 是否为空」：上下文 VO 是 NON_NULL 序列化，
        // 未绑号时 userPhone 整个字段消失，前端只能靠字段缺失猜语义，极易漏判成崩溃。
        ctx.setPhoneBound(StrUtil.isNotBlank(userPhone));
        // E2E-03 包B：配送能力投影接真（USER_BASE/COURIER_APPLY 基础 + 启用配送员 COURIER_WORK）；
        // 投影只改善导航不构成授权，配送接口数据范围仍由 CourierAccess 逐调用强制（铁律6/7）。
        ctx.setCapabilities(capabilityService.capabilitiesOf(userId));
        // E2E-06：机主授权范围随登录上下文下发（profile 展示；无归属不带字段）
        ctx.setOwnerScope(capabilityService.ownerScopeOf(userId));
        return ctx;
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
