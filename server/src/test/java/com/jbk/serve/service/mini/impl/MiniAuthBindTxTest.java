package com.jbk.serve.service.mini.impl;

import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.serve.service.mini.auth.BoundUser;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2-AUTH 绑定事务逻辑测试（复审 P0-2/P1-1）：mock 身份 Mapper，覆盖冲突矩阵与 CAS 影响行数语义。
 * 真实并发/唯一约束/@TableLogic 在 {@code MiniAuthIdentityDbTest} 用真实 MySQL 复现。
 */
class MiniAuthBindTxTest {

    private WsUserIdentityMapper identityMapper;
    private MiniAuthBindTxImpl bindTx;

    private static final String OPENID = "oABC-123";
    private static final String OTHER_OPENID = "oXYZ-999";
    private static final String PHONE = "13900001111";

    @BeforeEach
    void setup() {
        identityMapper = Mockito.mock(WsUserIdentityMapper.class);
        bindTx = new MiniAuthBindTxImpl(identityMapper);
        when(identityMapper.selectByOpenidIncludingDeleted(anyString())).thenReturn(new ArrayList<>());
        when(identityMapper.selectByPhoneIncludingDeleted(anyString())).thenReturn(new ArrayList<>());
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

    // openid 已绑定同手机号 → 幂等，无写入
    @Test
    void sameOpenidSamePhoneIsIdempotent() {
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID))
                .thenReturn(List.of(user(9L, OPENID, PHONE, 0, 1, 1)));

        BoundUser bound = bindTx.bind(OPENID, PHONE);

        assertEquals(9L, bound.id());
        verify(identityMapper, never()).bindOpenidToUsablePhoneUser(anyLong(), anyString(), anyString(), anyLong(), anyString());
        verify(identityMapper, never()).insertIdentityUser(any());
    }

    // openid 已绑定其他手机号 → 拒绝
    @Test
    void openidBoundToAnotherPhoneRejected() {
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID))
                .thenReturn(List.of(user(9L, OPENID, "13800008888", 0, 1, 1)));

        assertThrows(JbkException.class, () -> bindTx.bind(OPENID, PHONE));
        verify(identityMapper, never()).insertIdentityUser(any());
    }

    // openid 命中逻辑删除账号 → fail-closed（不建户）
    @Test
    void deletedOpenidRejected() {
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID))
                .thenReturn(List.of(user(9L, OPENID, PHONE, 1, 1, 1)));

        assertThrows(JbkException.class, () -> bindTx.bind(OPENID, PHONE));
        verify(identityMapper, never()).insertIdentityUser(any());
    }

    // openid 身份污染（>1）→ 拒绝
    @Test
    void openidPollutionRejected() {
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID))
                .thenReturn(List.of(user(9L, OPENID, PHONE, 0, 1, 1), user(10L, OPENID, "13800008888", 0, 1, 1)));

        assertThrows(JbkException.class, () -> bindTx.bind(OPENID, PHONE));
    }

    // 手机号已绑定其他 openid → 拒绝，不做 CAS
    @Test
    void phoneBoundToAnotherOpenidRejected() {
        when(identityMapper.selectByPhoneIncludingDeleted(PHONE))
                .thenReturn(List.of(user(7L, OTHER_OPENID, PHONE, 0, 1, 1)));

        assertThrows(JbkException.class, () -> bindTx.bind(OPENID, PHONE));
        verify(identityMapper, never()).bindOpenidToUsablePhoneUser(anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    // 手机号命中禁用账号 → fail-closed
    @Test
    void disabledPhoneUserRejected() {
        when(identityMapper.selectByPhoneIncludingDeleted(PHONE))
                .thenReturn(List.of(user(7L, null, PHONE, 0, 0, 1)));

        assertThrows(JbkException.class, () -> bindTx.bind(OPENID, PHONE));
        verify(identityMapper, never()).bindOpenidToUsablePhoneUser(anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    // 手机号已存在且未绑 openid + CAS 影响 1 行 → 成功
    @Test
    void bindExistingPhoneUserViaCasSucceeds() {
        when(identityMapper.selectByPhoneIncludingDeleted(PHONE))
                .thenReturn(List.of(user(7L, null, PHONE, 0, 1, 1)));
        when(identityMapper.bindOpenidToUsablePhoneUser(eq(7L), eq(PHONE), eq(OPENID), anyLong(), anyString()))
                .thenReturn(1);

        BoundUser bound = bindTx.bind(OPENID, PHONE);

        assertEquals(7L, bound.id());
        verify(identityMapper, never()).insertIdentityUser(any());
    }

    // CAS 影响 0 行（并发另一 openid 抢绑）→ fail-closed
    @Test
    void bindExistingPhoneUserCasZeroRowsRejected() {
        when(identityMapper.selectByPhoneIncludingDeleted(PHONE))
                .thenReturn(List.of(user(7L, null, PHONE, 0, 1, 1)));
        when(identityMapper.bindOpenidToUsablePhoneUser(eq(7L), eq(PHONE), eq(OPENID), anyLong(), anyString()))
                .thenReturn(0);

        assertThrows(JbkException.class, () -> bindTx.bind(OPENID, PHONE));
    }

    // 新手机号 → 建最小用户，字段合规
    @Test
    void createsMinimalUserForNewPhone() {
        when(identityMapper.insertIdentityUser(any())).thenAnswer(inv -> {
            WsUser u = inv.getArgument(0);
            u.setId(42L);
            return 1;
        });

        BoundUser bound = bindTx.bind(OPENID, PHONE);

        assertEquals(42L, bound.id());
        org.mockito.ArgumentCaptor<WsUser> cap = org.mockito.ArgumentCaptor.forClass(WsUser.class);
        verify(identityMapper).insertIdentityUser(cap.capture());
        WsUser created = cap.getValue();
        assertNull(created.getUserGender());
        assertEquals(1, created.getDisabledFlag());
        assertEquals(1, created.getUserStatus());
        assertEquals(0, created.getPoints());
        assertEquals(0, created.getDataStatus());
        assertEquals(PHONE, created.getUserPhone());
        assertEquals(OPENID, created.getWechatXcxOpenid());
    }

    // 建户命中唯一键（并发）→ fail-closed
    @Test
    void concurrentInsertDuplicateRejected() {
        when(identityMapper.insertIdentityUser(any())).thenThrow(new DuplicateKeyException("uk_user_phone"));

        assertThrows(JbkException.class, () -> bindTx.bind(OPENID, PHONE));
    }
}
