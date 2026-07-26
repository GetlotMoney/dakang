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
import com.jbk.tool.data.mini.bo.MiniLoginBo;
import com.jbk.tool.data.mini.vo.MiniAuthResultVo;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        service = new MiniAuthServiceImpl(identityMapper, code2Session, phone, issuer, bindTx, config,
                capabilityService);
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
}
