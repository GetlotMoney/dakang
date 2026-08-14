package com.jbk.serve.service.mini.auth;

import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.tool.exception.ErrorMsg;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 绑号闸本体的判据测试。
 *
 * <p>游客态下账号可以没有手机号（{@code mini.auth.phoneless-register.enabled=true} 时
 * 新 openid 直接建号、USER_PHONE 写 NULL），因此「未绑号」从异常变成了常态输入，
 * 这道闸决定它能不能碰钱与履约归属。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
@DisplayName("绑号闸判据")
class MiniPhoneGateTest {

    /** 闸只读手机号这一列，故 mock 直接给字符串；null 表示账号不存在或未绑号。 */
    private static MiniPhoneGate gateReturning(String phone) {
        WsUserIdentityMapper mapper = Mockito.mock(WsUserIdentityMapper.class);
        Mockito.when(mapper.selectPhoneByIdIncludingDeleted(Mockito.anyLong())).thenReturn(phone);
        return new MiniPhoneGate(mapper);
    }

    @Test
    @DisplayName("已绑号放行")
    void boundPhonePasses() {
        assertDoesNotThrow(() ->
                gateReturning(("13900000001")).requirePhoneBound(1L, "测试"));
    }

    @Test
    @DisplayName("NULL 手机号拒绝，且用独立错误码")
    void nullPhoneRejected() {
        JbkException e = assertThrows(JbkException.class, () ->
                gateReturning(null).requirePhoneBound(1L, "测试"));
        // 错误码必须独立：并入 540 会让这类正常业务拒绝计入 IP 异常封禁计数，
        // 而社区售水机用户共用出口 IP 是常态，几十个未绑号用户就能把整栋楼封掉
        assertEquals(ErrorMsg.PHONE_BIND_REQUIRED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("空串手机号同样拒绝")
    void blankPhoneRejected() {
        // 建号链写的是 NULL（空串之间会互撞 uk_user_phone），但历史数据不保证；
        // 只判 null 会让一条空串脏数据直接穿过整道闸
        assertThrows(JbkException.class, () ->
                gateReturning(("")).requirePhoneBound(1L, "测试"));
        assertThrows(JbkException.class, () ->
                gateReturning(("   ")).requirePhoneBound(1L, "测试"));
    }

    @Test
    @DisplayName("无会话与账号不存在都拒绝，不得当成已绑号放行")
    void missingIdentityRejected() {
        assertThrows(JbkException.class, () ->
                gateReturning(("13900000001")).requirePhoneBound(null, "测试"));
        assertThrows(JbkException.class, () ->
                gateReturning(null).requirePhoneBound(1L, "测试"));
    }

    @Test
    @DisplayName("isPhoneBound 只问不拦，且与 require 判据一致")
    void queryMatchesRequire() {
        assertEquals(true, gateReturning(("13900000001")).isPhoneBound(1L));
        assertEquals(false, gateReturning(null).isPhoneBound(1L));
        assertEquals(false, gateReturning(("")).isPhoneBound(1L));
        assertEquals(false, gateReturning(null).isPhoneBound(1L));
        assertEquals(false, gateReturning(("13900000001")).isPhoneBound(null));
    }
}
