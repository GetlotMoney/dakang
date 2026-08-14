package com.jbk.serve.service.mini.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.serve.service.mini.IMiniCapabilityService;
import com.jbk.serve.service.mini.auth.BoundUser;
import com.jbk.serve.service.mini.auth.IKhUserSessionIssuer;
import com.jbk.serve.service.mini.auth.IMiniAuthBindTx;
import com.jbk.serve.service.mini.auth.IWechatCode2SessionAdapter;
import com.jbk.serve.service.mini.auth.IWechatPhoneAdapter;
import com.jbk.serve.service.mini.auth.KhUserSession;
import com.jbk.serve.service.mini.auth.WechatCode2SessionResult;
import com.jbk.tool.config.wechat.WechatXcxConfig;
import com.jbk.tool.data.mini.bo.MiniBindPhoneBo;
import com.jbk.tool.data.mini.bo.MiniBindPhoneSelfBo;
import com.jbk.tool.data.mini.bo.MiniLoginBo;
import com.jbk.tool.data.mini.vo.MiniAuthResultVo;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2-AUTH 编排层测试（纯 Mockito）：不外呼微信、不连真实库、不建真实会话。
 *
 * <p>身份读取走 {@link WsUserIdentityMapper}（跨全部 DATA_STATUS）；绑定写入委托 {@link IMiniAuthBindTx}
 * 独立事务，本层只在事务提交（bind 返回）后签发 Token。绑定内部的冲突/并发/@TableLogic 由
 * {@link MiniAuthBindTxTest} 与真实库集成测试 {@code MiniAuthIdentityDbTest} 覆盖。</p>
 */
class MiniAuthServiceTest {

    private WsUserIdentityMapper identityMapper;
    private IWechatCode2SessionAdapter code2Session;
    private IWechatPhoneAdapter phone;
    private IKhUserSessionIssuer issuer;
    private IMiniAuthBindTx bindTx;
    /** D-418 赠卡发放协作者：本测试断言的是**何时**调它，不是它内部怎么发。 */
    private com.jbk.serve.service.settlement.IRegisterGiftService registerGift;
    private WechatXcxConfig config;
    private IMiniCapabilityService capabilityService;
    private RedisTemplate<String, Object> redis;
    private ValueOperations<String, Object> valueOps;
    private MiniAuthServiceImpl service;

    private static final String APPID = "wx-test-appid";
    private static final String OPENID = "oABC-123";
    private static final String PHONE = "13900001111";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        identityMapper = Mockito.mock(WsUserIdentityMapper.class);
        code2Session = Mockito.mock(IWechatCode2SessionAdapter.class);
        phone = Mockito.mock(IWechatPhoneAdapter.class);
        issuer = Mockito.mock(IKhUserSessionIssuer.class);
        bindTx = Mockito.mock(IMiniAuthBindTx.class);
        config = Mockito.mock(WechatXcxConfig.class);
        redis = Mockito.mock(RedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(config.getAppid()).thenReturn(APPID);
        when(issuer.issue(any(), any())).thenReturn(new KhUserSession("dakang-token", "TOKEN-KH"));
        // E2E-03 包B：能力投影为独立协作者；本测试只关心登录编排，投影固定返回基础能力
        capabilityService = Mockito.mock(IMiniCapabilityService.class);
        when(capabilityService.capabilitiesOf(any())).thenReturn(List.of("USER_BASE"));
        registerGift = Mockito.mock(com.jbk.serve.service.settlement.IRegisterGiftService.class);
        service = new MiniAuthServiceImpl(identityMapper, code2Session, phone, issuer, bindTx, config,
                capabilityService, registerGift);
        ReflectionTestUtils.setField(service, "redis", redis);
    }

    private WsUser user(Long id, String openid, String phone, int dataStatus, int disabled, int status) {
        WsUser u = new WsUser();
        u.setId(id);
        u.setUserName("张三");
        u.setWechatXcxOpenid(openid);
        u.setUserPhone(phone);
        u.setDataStatus(dataStatus);
        u.setDisabledFlag(disabled);
        u.setUserStatus(status);
        return u;
    }

    private MiniLoginBo loginBo() {
        MiniLoginBo bo = new MiniLoginBo();
        bo.setCode("code-x");
        return bo;
    }

    private MiniBindPhoneBo bindBo(String ticket) {
        MiniBindPhoneBo bo = new MiniBindPhoneBo();
        bo.setBindTicket(ticket);
        bo.setPhoneCode("phone-code-x");
        return bo;
    }

    private String ticketJson(String openid, String purpose, String appid) {
        return "{\"appid\":\"" + appid + "\",\"openid\":\"" + openid + "\",\"purpose\":\"" + purpose
                + "\",\"issuedAt\":\"20260721000000\"}";
    }

    // 1) 已绑定正常用户登录成功
    @Test
    void boundUserLoginSucceeds() {
        when(code2Session.resolve("code-x")).thenReturn(new WechatCode2SessionResult(OPENID, "sk"));
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID)).thenReturn(List.of(user(9L, OPENID, PHONE, 0, 1, 1)));

        MiniAuthResultVo vo = service.login(loginBo());

        assertEquals("BOUND", vo.getResult());
        assertEquals("dakang-token", vo.getTokenName());
        assertEquals("TOKEN-KH", vo.getTokenValue());
        assertEquals("9", vo.getAccountContext().getUserId());
        verify(issuer).issue(eq(9L), any());
    }

    // 2) 未绑定 openid 返回一次性 bindTicket
    @Test
    void unboundOpenidReturnsTicket() {
        when(code2Session.resolve("code-x")).thenReturn(new WechatCode2SessionResult(OPENID, "sk"));
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID)).thenReturn(new ArrayList<>());

        MiniAuthResultVo vo = service.login(loginBo());

        assertEquals("UNBOUND", vo.getResult());
        assertNotNull(vo.getBindTicket());
        assertEquals(300L, vo.getExpiresInSeconds());
        assertEquals(32, vo.getBindTicket().length()); // 128bit 十六进制 = 32 字符
        verify(issuer, never()).issue(anyLong(), any());
    }

    // 3) 禁用/注销/逻辑删除用户拒绝登录（跨状态可见 → assertUsable fail-closed）
    @Test
    void disabledOrDeletedUserRejectedOnLogin() {
        when(code2Session.resolve("code-x")).thenReturn(new WechatCode2SessionResult(OPENID, "sk"));
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID)).thenReturn(List.of(user(9L, OPENID, PHONE, 0, 0, 1)));
        assertThrows(JbkException.class, () -> service.login(loginBo()));
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID)).thenReturn(List.of(user(9L, OPENID, PHONE, 1, 1, 1)));
        assertThrows(JbkException.class, () -> service.login(loginBo()));
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID)).thenReturn(List.of(user(9L, OPENID, PHONE, 0, 1, 2)));
        assertThrows(JbkException.class, () -> service.login(loginBo()));
        verify(issuer, never()).issue(anyLong(), any());
    }

    // 4) 逻辑删除 openid 不得被误判为 UNBOUND（复审 P1-1：跨状态可见 → 拒绝，而非下发票据建户）
    @Test
    void deletedOpenidNotTreatedAsUnbound() {
        when(code2Session.resolve("code-x")).thenReturn(new WechatCode2SessionResult(OPENID, "sk"));
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID)).thenReturn(List.of(user(9L, OPENID, PHONE, 1, 1, 1)));

        assertThrows(JbkException.class, () -> service.login(loginBo()));
        // 不得下发绑定票据（即不得进入 UNBOUND 建户路径）。
        verify(valueOps, never()).set(anyString(), any(), anyLong(), any());
        verify(issuer, never()).issue(anyLong(), any());
    }

    // 5) openid 身份污染（>1 条）→ fail-closed 拒绝，不猜测归属
    @Test
    void openidIdentityPollutionRejected() {
        when(code2Session.resolve("code-x")).thenReturn(new WechatCode2SessionResult(OPENID, "sk"));
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID))
                .thenReturn(List.of(user(9L, OPENID, PHONE, 0, 1, 1), user(10L, OPENID, "13800008888", 0, 1, 1)));

        assertThrows(JbkException.class, () -> service.login(loginBo()));
        verify(issuer, never()).issue(anyLong(), any());
    }

    // 6) ticket 过期 / purpose 错位 / appid 错位均拒绝，且不解析手机号、不进绑定事务
    @Test
    void ticketExpiredOrWrongPurposeRejected() {
        when(valueOps.getAndDelete(anyString())).thenReturn(null);
        assertThrows(JbkException.class, () -> service.bindPhone(bindBo("t1")));

        when(valueOps.getAndDelete(anyString())).thenReturn(ticketJson(OPENID, "reset-pwd", APPID));
        assertThrows(JbkException.class, () -> service.bindPhone(bindBo("t2")));

        when(valueOps.getAndDelete(anyString())).thenReturn(ticketJson(OPENID, "bind-phone", "wx-other"));
        assertThrows(JbkException.class, () -> service.bindPhone(bindBo("t3")));

        verify(phone, never()).resolvePhone(anyString());
        verify(bindTx, never()).bind(anyString(), anyString());
    }

    // 7) 绑定成功：事务提交（bindTx.bind 返回）后才签发 Token，且顺序为「先绑定后签发」
    @Test
    void bindPhoneIssuesTokenAfterCommit() {
        when(valueOps.getAndDelete(anyString())).thenReturn(ticketJson(OPENID, "bind-phone", APPID));
        when(phone.resolvePhone("phone-code-x")).thenReturn(PHONE);
        when(bindTx.bind(OPENID, PHONE)).thenReturn(new BoundUser(15L, "用户1111", PHONE));

        MiniAuthResultVo vo = service.bindPhone(bindBo("t-ok"));

        assertEquals("BOUND", vo.getResult());
        assertEquals("15", vo.getAccountContext().getUserId());
        InOrder order = inOrder(bindTx, issuer);
        order.verify(bindTx).bind(OPENID, PHONE);
        order.verify(issuer).issue(eq(15L), any());
    }

    // 8) 绑定事务失败（提交失败/冲突）→ 绝不签发 Token（复审 P0-2 核心）
    @Test
    void tokenNotIssuedWhenBindTxFails() {
        when(valueOps.getAndDelete(anyString())).thenReturn(ticketJson(OPENID, "bind-phone", APPID));
        when(phone.resolvePhone("phone-code-x")).thenReturn(PHONE);
        when(bindTx.bind(OPENID, PHONE)).thenThrow(new JbkException("绑定冲突，请重新登录后再试"));

        assertThrows(JbkException.class, () -> service.bindPhone(bindBo("t")));
        verify(issuer, never()).issue(anyLong(), any());
    }

    // 9) 事务失败后 ticket 不能重放（GETDEL 已消费，二次领取得 null）
    @Test
    void ticketNotReplayableAfterTxnFailure() {
        when(valueOps.getAndDelete(anyString()))
                .thenReturn(ticketJson(OPENID, "bind-phone", APPID))
                .thenReturn(null);
        when(phone.resolvePhone("phone-code-x")).thenReturn(PHONE);
        when(bindTx.bind(OPENID, PHONE)).thenThrow(new JbkException("txn fail"));

        assertThrows(JbkException.class, () -> service.bindPhone(bindBo("t")));
        assertThrows(JbkException.class, () -> service.bindPhone(bindBo("t")));
        verify(issuer, never()).issue(anyLong(), any());
    }

    // 10) 响应 JSON 不包含 openid、session_key
    @Test
    void responseHasNoIdentitySecrets() throws Exception {
        when(code2Session.resolve("code-x")).thenReturn(new WechatCode2SessionResult(OPENID, "sk"));
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID)).thenReturn(List.of(user(9L, OPENID, PHONE, 0, 1, 1)));
        MiniAuthResultVo vo = service.login(loginBo());

        String json = new ObjectMapper().writeValueAsString(vo).toLowerCase();
        assertFalse(json.contains("openid"), "响应体不得包含 openid");
        assertFalse(json.contains("session_key"), "响应体不得包含 session_key");
        assertFalse(json.contains("sessionkey"), "响应体不得包含 sessionKey");
    }

    // 11) Token 经 KH_USER 会话签发器建立（不建后台账号会话）
    @Test
    void tokenIssuedViaKhUserIssuer() {
        when(code2Session.resolve("code-x")).thenReturn(new WechatCode2SessionResult(OPENID, "sk"));
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID)).thenReturn(List.of(user(9L, OPENID, PHONE, 0, 1, 1)));
        MiniAuthResultVo vo = service.login(loginBo());
        assertEquals("dakang-token", vo.getTokenName());
        verify(issuer).issue(eq(9L), any());
    }

    // 12) 旧鉴权入口类全部移除、新 /mini/auth 入口存在（复审 P0-1：部署产物禁令类兜底）
    @Test
    void legacyAuthEntrypointsRemovedAndNewOnesPresent() throws Exception {
        for (String banned : List.of(
                "com.jbk.serve.controller.wechat.WechatXcxController",
                "com.jbk.serve.service.wechat.IWechatXcxService",
                "com.jbk.serve.service.wechat.impl.WechatXcxServiceImpl",
                "com.jbk.serve.controller.mini.MiniDevAuthController",
                "com.jbk.tool.data.mini.bo.MiniDevLoginBo",
                "com.jbk.tool.data.mini.vo.MiniDevLoginVo")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(banned), banned + " 仍存在");
        }
        assertNotNull(Class.forName("com.jbk.serve.controller.mini.MiniAuthController"));
    }

    // ---------- 仅微信身份建号开关 ----------

    // 13) 开关关闭（默认）：新 openid 仍走 UNBOUND 票据路径，绝不悄悄建号
    @Test
    void phonelessRegisterOffKeepsTicketPath() {
        when(code2Session.resolve("code-x")).thenReturn(new WechatCode2SessionResult(OPENID, "sk"));
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID)).thenReturn(new ArrayList<>());

        MiniAuthResultVo vo = service.login(loginBo());

        assertEquals("UNBOUND", vo.getResult());
        verify(bindTx, never()).registerByOpenid(anyString());
    }

    // 14) 开关打开：新 openid 直接建号并 BOUND；上下文标记未绑号且不携带 userPhone
    @Test
    void phonelessRegisterOnCreatesAccountAndBinds() {
        ReflectionTestUtils.setField(service, "phonelessRegisterEnabled", true);
        when(code2Session.resolve("code-x")).thenReturn(new WechatCode2SessionResult(OPENID, "sk"));
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID)).thenReturn(new ArrayList<>());
        when(bindTx.registerByOpenid(OPENID)).thenReturn(new BoundUser(66L, "微信用户", null));

        MiniAuthResultVo vo = service.login(loginBo());

        assertEquals("BOUND", vo.getResult());
        assertEquals("66", vo.getAccountContext().getUserId());
        assertNull(vo.getAccountContext().getUserPhone());
        assertFalse(vo.getAccountContext().getPhoneBound(), "未绑号必须显式标 false，不让前端靠字段缺失猜");
        verify(issuer).issue(eq(66L), any());
    }

    // 15) 开关打开但 openid 已建号：走既有账号，不重复建号
    @Test
    void phonelessRegisterOnStillPrefersExistingAccount() {
        ReflectionTestUtils.setField(service, "phonelessRegisterEnabled", true);
        when(code2Session.resolve("code-x")).thenReturn(new WechatCode2SessionResult(OPENID, "sk"));
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID))
                .thenReturn(List.of(user(9L, OPENID, PHONE, 0, 1, 1)));

        MiniAuthResultVo vo = service.login(loginBo());

        assertEquals("BOUND", vo.getResult());
        assertTrue(vo.getAccountContext().getPhoneBound());
        verify(bindTx, never()).registerByOpenid(anyString());
    }

    // 16) 开关打开也不得放行禁用/删除账号（建号路径不是绕过封禁的后门）
    @Test
    void phonelessRegisterOnStillRejectsUnusableAccount() {
        ReflectionTestUtils.setField(service, "phonelessRegisterEnabled", true);
        when(code2Session.resolve("code-x")).thenReturn(new WechatCode2SessionResult(OPENID, "sk"));
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID))
                .thenReturn(List.of(user(9L, OPENID, PHONE, 1, 1, 1)));

        assertThrows(JbkException.class, () -> service.login(loginBo()));
        verify(bindTx, never()).registerByOpenid(anyString());
        verify(issuer, never()).issue(anyLong(), any());
    }

    // ---------- 登录后自助补绑 ----------

    // 17) 补绑成功：号码由服务端换取，返回刷新后的上下文，且不换发会话
    @Test
    void bindPhoneForCurrentUserRefreshesContextWithoutNewSession() {
        when(phone.resolvePhone("phone-code-x")).thenReturn(PHONE);
        when(bindTx.bindPhoneToCurrentUser(66L, PHONE)).thenReturn(new BoundUser(66L, "微信用户", PHONE));

        MiniBindPhoneSelfBo bo = new MiniBindPhoneSelfBo();
        bo.setPhoneCode("phone-code-x");
        var ctx = service.bindPhoneForCurrentUser(66L, bo);

        assertEquals("66", ctx.getUserId());
        assertEquals(PHONE, ctx.getUserPhone());
        assertTrue(ctx.getPhoneBound());
        verify(issuer, never()).issue(anyLong(), any());
    }

    // 18) 补绑失败不得改上下文（事务抛异常即整体失败）
    @Test
    void bindPhoneForCurrentUserFailClosed() {
        when(phone.resolvePhone("phone-code-x")).thenReturn(PHONE);
        when(bindTx.bindPhoneToCurrentUser(66L, PHONE)).thenThrow(new JbkException("该手机号已被其他账号使用"));

        MiniBindPhoneSelfBo bo = new MiniBindPhoneSelfBo();
        bo.setPhoneCode("phone-code-x");
        assertThrows(JbkException.class, () -> service.bindPhoneForCurrentUser(66L, bo));
    }

    // ========== D-418 赠卡挂点（游客态下从「建号」后移到「绑号」）==========

    @Test
    @DisplayName("仅微信身份建号不发赠卡：不可联系、不可找回的账号不得先拿到可兑付权益")
    void phonelessRegisterGrantsNoGift() {
        ReflectionTestUtils.setField(service, "phonelessRegisterEnabled", true);
        when(code2Session.resolve("code-x")).thenReturn(new WechatCode2SessionResult(OPENID, null));
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID)).thenReturn(List.of());
        when(bindTx.registerByOpenid(OPENID))
                .thenReturn(new BoundUser(7L, "微信用户", null, true));
        when(identityMapper.selectByIdIncludingDeleted(7L)).thenReturn(user(7L, OPENID, null, 0, 0, 1));

        service.login(loginBo());

        // 挂点若留在建号分支：限流器从实名 SIM 降级成微信号，而赠卡可直接在售水机取水、
        // 不占付费卡名额、耗尽后还能充值转永久付费卡——薅取成本降到"再注册一个微信号"
        verify(registerGift, never()).grantIfEnabled(anyLong());
    }

    @Test
    @DisplayName("票据链绑号成功即发赠卡，且不看 newlyCreated")
    void bindPhoneGrantsGift() {
        when(valueOps.getAndDelete(anyString())).thenReturn(ticketJson(OPENID, "bind-phone", APPID));
        when(phone.resolvePhone("phone-code-x")).thenReturn("13900000001");
        // 游客态下账号早在 openid 首登时就建好了，这里恒为 false——
        // 若仍按 newlyCreated 判定，走这条链绑号的用户一张卡都拿不到
        // 游客态：账号早已建好（newlyCreated=false），但本次让它首次拥有手机号
        when(bindTx.bind(OPENID, "13900000001"))
                .thenReturn(new BoundUser(7L, "微信用户", "13900000001", false, true));
        when(identityMapper.selectByIdIncludingDeleted(7L))
                .thenReturn(user(7L, OPENID, "13900000001", 0, 0, 1));

        service.bindPhone(bindBo("TICKET"));

        verify(registerGift).grantIfEnabled(7L);
    }

    @Test
    @DisplayName("已有会话补绑手机号同样发赠卡（游客态的主要动线）")
    void selfBindGrantsGift() {
        MiniBindPhoneSelfBo selfBo = new MiniBindPhoneSelfBo();
        selfBo.setPhoneCode("phone-code-x");
        when(phone.resolvePhone("phone-code-x")).thenReturn("13900000002");
        when(bindTx.bindPhoneToCurrentUser(7L, "13900000002"))
                .thenReturn(new BoundUser(7L, "微信用户", "13900000002", false, true));
        when(identityMapper.selectByIdIncludingDeleted(7L))
                .thenReturn(user(7L, OPENID, "13900000002", 0, 0, 1));

        service.bindPhoneForCurrentUser(7L, selfBo);

        verify(registerGift).grantIfEnabled(7L);
    }

    @Test
    @DisplayName("幂等复绑同一号码不再发第二张：绑定成功 ≠ 完成了一次注册")
    void idempotentRebindGrantsNothing() {
        when(valueOps.getAndDelete(anyString())).thenReturn(ticketJson(OPENID, "bind-phone", APPID));
        when(phone.resolvePhone("phone-code-x")).thenReturn("13900000001");
        // 三参构造器 = 幂等/复用路径，两个标志都为 false
        when(bindTx.bind(OPENID, "13900000001"))
                .thenReturn(new BoundUser(7L, "微信用户", "13900000001"));
        when(identityMapper.selectByIdIncludingDeleted(7L))
                .thenReturn(user(7L, OPENID, "13900000001", 0, 0, 1));

        service.bindPhone(bindBo("TICKET"));

        // 判据若写成「调用成功就发」，这里会发出第二张——幂等键能挡住重复入账，
        // 但账号在语义上变成了「每次绑号都算注册」，键规则一变就漏
        verify(registerGift, never()).grantIfEnabled(anyLong());
    }

    @Test
    @DisplayName("存量老用户首次用微信登录不发赠卡：那是挂 openid，不是注册")
    void existingPhoneUserBindingOpenidGrantsNothing() {
        when(valueOps.getAndDelete(anyString())).thenReturn(ticketJson(OPENID, "bind-phone", APPID));
        when(phone.resolvePhone("phone-code-x")).thenReturn("13900000001");
        // 存量账号本来就有手机号，本次只是把 openid 挂上去：phoneNewlyBound=false
        when(bindTx.bind(OPENID, "13900000001"))
                .thenReturn(new BoundUser(7L, "老用户", "13900000001", false, false));
        when(identityMapper.selectByIdIncludingDeleted(7L))
                .thenReturn(user(7L, OPENID, "13900000001", 0, 0, 1));

        service.bindPhone(bindBo("TICKET"));

        verify(registerGift, never()).grantIfEnabled(anyLong());
    }
}
