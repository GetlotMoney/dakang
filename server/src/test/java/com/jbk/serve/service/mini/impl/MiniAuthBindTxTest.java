package com.jbk.serve.service.mini.impl;

import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.serve.service.mini.auth.BoundUser;
import com.jbk.serve.service.mini.auth.MiniIdentityConflictRecorder;
import com.jbk.tool.consts.mini.MiniIdentityConflictEnum.Scene;
import com.jbk.tool.consts.mini.MiniIdentityConflictEnum.Type;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private MiniIdentityConflictRecorder conflictRecorder;
    private MiniAuthBindTxImpl bindTx;

    private static final String OPENID = "oABC-123";
    private static final String OTHER_OPENID = "oXYZ-999";
    private static final String PHONE = "13900001111";

    @BeforeEach
    void setup() {
        identityMapper = Mockito.mock(WsUserIdentityMapper.class);
        conflictRecorder = Mockito.mock(MiniIdentityConflictRecorder.class);
        bindTx = new MiniAuthBindTxImpl(identityMapper, conflictRecorder);
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

    // openid 已绑定其他手机号 → 拒绝，且必须留下可处置的台账
    @Test
    void openidBoundToAnotherPhoneRejected() {
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID))
                .thenReturn(List.of(user(9L, OPENID, "13800008888", 0, 1, 1)));

        assertThrows(JbkException.class, () -> bindTx.bind(OPENID, PHONE));
        verify(identityMapper, never()).insertIdentityUser(any());
        // 拒绝之外还必须留痕：否则客服接到电话时手上没有任何信息
        //（任务书 S1「冲突必须 fail-closed，并进入可审计的人工处理状态」）。
        // 持有方是命中的账号 9；发起方此刻还没有账号，故传 null（落库记 0 哨兵）。
        verify(conflictRecorder).record(eq(Type.OPENID_BOUND_OTHER_PHONE), eq(Scene.LOGIN_BIND),
                eq(9L), eq(null), eq(PHONE));
    }

    /**
     * 幂等命中不是冲突：同 openid 同手机号重复提交必须<b>不</b>留台账。
     *
     * <p>没有这条反向断言，把 record 无脑挂在方法开头也会让其余几条冲突断言全绿——
     * 而那样运营的待办会被正常重复提交淹掉，真正的冲突反而看不见。</p>
     */
    @Test
    void idempotentRebindRecordsNoConflict() {
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID))
                .thenReturn(List.of(user(9L, OPENID, PHONE, 0, 1, 1)));

        bindTx.bind(OPENID, PHONE);

        verify(conflictRecorder, never()).record(any(), any(), any(), any(), anyString());
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

    // 手机号已绑定其他 openid → 拒绝，不做 CAS，且留台账
    @Test
    void phoneBoundToAnotherOpenidRejected() {
        when(identityMapper.selectByPhoneIncludingDeleted(PHONE))
                .thenReturn(List.of(user(7L, OTHER_OPENID, PHONE, 0, 1, 1)));

        assertThrows(JbkException.class, () -> bindTx.bind(OPENID, PHONE));
        verify(identityMapper, never()).bindOpenidToUsablePhoneUser(anyLong(), anyString(), anyString(), anyLong(), anyString());
        verify(conflictRecorder).record(eq(Type.PHONE_BOUND_OTHER_WECHAT), eq(Scene.LOGIN_BIND),
                eq(7L), eq(null), eq(PHONE));
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

    // ---------- 仅微信身份建号（registerByOpenid） ----------

    // 新 openid → 建号且手机号写 NULL（写空串会让第二个未绑用户撞 uk_user_phone）
    @Test
    void registerByOpenidCreatesUserWithNullPhone() {
        when(identityMapper.insertIdentityUser(any())).thenAnswer(inv -> {
            WsUser u = inv.getArgument(0);
            u.setId(77L);
            return 1;
        });
        when(identityMapper.renamePlaceholderUserName(eq(77L), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(1);

        BoundUser bound = bindTx.registerByOpenid(OPENID);

        assertEquals(77L, bound.id());
        assertNull(bound.userPhone());
        org.mockito.ArgumentCaptor<WsUser> cap = org.mockito.ArgumentCaptor.forClass(WsUser.class);
        verify(identityMapper).insertIdentityUser(cap.capture());
        WsUser created = cap.getValue();
        assertNull(created.getUserPhone(), "未绑手机号必须写 NULL，绝不能写空串");
        assertEquals(OPENID, created.getWechatXcxOpenid());
        assertEquals(1, created.getDisabledFlag());
        assertEquals(1, created.getUserStatus());
        assertEquals(0, created.getDataStatus());
    }

    // 建号昵称必须带 ID 尾号：一排同名且无手机号的账号，运营在 PC 上分不清谁是谁，
    // 开配送员权限就可能开给错的人——而配送任务带着收货地址与联系电话。
    @Test
    void registerByOpenidNamesAccountWithIdSuffix() {
        when(identityMapper.insertIdentityUser(any())).thenAnswer(inv -> {
            WsUser u = inv.getArgument(0);
            u.setId(123456L);
            return 1;
        });
        when(identityMapper.renamePlaceholderUserName(eq(123456L), eq("微信用户"), eq("微信用户3456"),
                anyLong(), anyString())).thenReturn(1);

        BoundUser bound = bindTx.registerByOpenid(OPENID);

        assertEquals("微信用户3456", bound.userName());
        verify(identityMapper).renamePlaceholderUserName(
                eq(123456L), eq("微信用户"), eq("微信用户3456"), anyLong(), anyString());
    }

    // 改名守卫未命中（昵称已被改过/行不存在）→ 保留占位名，绝不因此让登录失败
    @Test
    void registerByOpenidSurvivesRenameMiss() {
        when(identityMapper.insertIdentityUser(any())).thenAnswer(inv -> {
            WsUser u = inv.getArgument(0);
            u.setId(88L);
            return 1;
        });
        when(identityMapper.renamePlaceholderUserName(anyLong(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(0);

        BoundUser bound = bindTx.registerByOpenid(OPENID);

        assertEquals(88L, bound.id());
        assertEquals("微信用户", bound.userName());
    }

    // openid 已建号 → 幂等返回，不重复插入
    @Test
    void registerByOpenidIsIdempotent() {
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID))
                .thenReturn(List.of(user(9L, OPENID, null, 0, 1, 1)));

        BoundUser bound = bindTx.registerByOpenid(OPENID);

        assertEquals(9L, bound.id());
        verify(identityMapper, never()).insertIdentityUser(any());
    }

    // openid 命中禁用/删除账号 → fail-closed，绝不另建新号绕过封禁
    @Test
    void registerByOpenidRejectsUnusableAccount() {
        when(identityMapper.selectByOpenidIncludingDeleted(OPENID))
                .thenReturn(List.of(user(9L, OPENID, PHONE, 1, 1, 1)));

        assertThrows(JbkException.class, () -> bindTx.registerByOpenid(OPENID));
        verify(identityMapper, never()).insertIdentityUser(any());
    }

    // 并发同 openid 撞唯一键 → 锁定读重读胜出行返回（正常竞态，不是错误）
    @Test
    void registerByOpenidRecoversFromConcurrentInsert() {
        when(identityMapper.insertIdentityUser(any()))
                .thenThrow(new DuplicateKeyException("uk_user_wechat_xcx_openid"));
        when(identityMapper.selectByOpenidForUpdate(OPENID))
                .thenReturn(List.of(user(88L, OPENID, null, 0, 1, 1)));

        BoundUser bound = bindTx.registerByOpenid(OPENID);

        assertEquals(88L, bound.id());
    }

    // 撞的不是 openid 唯一键（重读为空）→ 不猜原因，fail-closed
    @Test
    void registerByOpenidRejectsWhenDuplicateIsNotOpenid() {
        when(identityMapper.insertIdentityUser(any()))
                .thenThrow(new DuplicateKeyException("uk_user_invite_code"));
        when(identityMapper.selectByOpenidForUpdate(OPENID)).thenReturn(new ArrayList<>());

        JbkException ex = assertThrows(JbkException.class, () -> bindTx.registerByOpenid(OPENID));
        // 认理由而非只认类型：必须是「建号冲突」而不是被别处的空值检查顺手兜住
        assertTrue(ex.getMessage().contains("建号冲突"), "实际=" + ex.getMessage());
    }

    // ---------- 登录后自助补绑（bindPhoneToCurrentUser） ----------

    // 号码无主 + CAS 影响 1 行 → 成功；UPDATE_BY 必须记本人（自助行为不得记成系统操作）
    @Test
    void bindPhoneToCurrentUserSucceeds() {
        when(identityMapper.bindPhoneToPhonelessUser(eq(55L), eq(PHONE), eq(55L), anyString())).thenReturn(1);
        when(identityMapper.selectByPhoneIncludingDeleted(PHONE))
                .thenReturn(new ArrayList<>())
                .thenReturn(List.of(user(55L, OPENID, PHONE, 0, 1, 1)));

        BoundUser bound = bindTx.bindPhoneToCurrentUser(55L, PHONE);

        assertEquals(55L, bound.id());
        assertEquals(PHONE, bound.userPhone());
    }

    // 号码已归属他人 → 拒绝，不做 CAS（两个账号各带卡券余额，合并不是登录链路能承担的动作）
    @Test
    void bindPhoneToCurrentUserRejectsPhoneOwnedByOthers() {
        when(identityMapper.selectByPhoneIncludingDeleted(PHONE))
                .thenReturn(List.of(user(7L, OTHER_OPENID, PHONE, 0, 1, 1)));

        assertThrows(JbkException.class, () -> bindTx.bindPhoneToCurrentUser(55L, PHONE));
        verify(identityMapper, never()).bindPhoneToPhonelessUser(anyLong(), anyString(), anyLong(), anyString());
    }

    // 本人重复提交同号 → 幂等返回，不再写
    @Test
    void bindPhoneToCurrentUserIsIdempotentForSelf() {
        when(identityMapper.selectByPhoneIncludingDeleted(PHONE))
                .thenReturn(List.of(user(55L, OPENID, PHONE, 0, 1, 1)));

        BoundUser bound = bindTx.bindPhoneToCurrentUser(55L, PHONE);

        assertEquals(55L, bound.id());
        verify(identityMapper, never()).bindPhoneToPhonelessUser(anyLong(), anyString(), anyLong(), anyString());
    }

    /**
     * CAS 影响 0 行（本账号已绑号/状态异常）→ fail-closed，换绑不从此路进。
     *
     * <p>必须断言异常<b>理由</b>而非只断言抛没抛：变异测试证实过，把影响行数检查放宽成 {@code < 0}
     * 时本用例仍然全绿——0 行会一路走到后面的重读，因读不到行同样抛 JbkException，
     * 类型对了理由全错，等于 CAS 这道闸门在测试上没人把守。</p>
     */
    @Test
    void bindPhoneToCurrentUserCasZeroRowsRejected() {
        when(identityMapper.bindPhoneToPhonelessUser(eq(55L), eq(PHONE), anyLong(), anyString())).thenReturn(0);
        when(identityMapper.selectByIdIncludingDeleted(55L))
                .thenReturn(user(55L, OPENID, "13800008888", 0, 1, 1)); // 已绑号

        JbkException ex = assertThrows(JbkException.class, () -> bindTx.bindPhoneToCurrentUser(55L, PHONE));
        // 出路必须是「刷新」而不是笼统失败——已绑号刷新即可，说成失败用户只会一直重试
        assertTrue(ex.getMessage().contains("刷新"), "实际=" + ex.getMessage());
    }

    // CAS 0 行且账号已被禁用/删除 → 出路是找客服，不能与「刷新」混为一谈
    @Test
    void bindPhoneToCurrentUserCasZeroRowsOnUnusableAccountTellsSupport() {
        when(identityMapper.bindPhoneToPhonelessUser(eq(55L), eq(PHONE), anyLong(), anyString())).thenReturn(0);
        when(identityMapper.selectByIdIncludingDeleted(55L))
                .thenReturn(user(55L, OPENID, null, 1, 1, 1)); // 逻辑删除且无号

        JbkException ex = assertThrows(JbkException.class, () -> bindTx.bindPhoneToCurrentUser(55L, PHONE));
        assertTrue(ex.getMessage().contains("联系客服"), "实际=" + ex.getMessage());
    }

    // 读到无主、写入前被并发抢注 → 唯一键兜底
    @Test
    void bindPhoneToCurrentUserRejectsOnDuplicateKey() {
        when(identityMapper.bindPhoneToPhonelessUser(eq(55L), eq(PHONE), anyLong(), anyString()))
                .thenThrow(new DuplicateKeyException("uk_user_phone"));

        JbkException ex = assertThrows(JbkException.class, () -> bindTx.bindPhoneToCurrentUser(55L, PHONE));
        // 用户要看到的是「号被别人占了」，不是笼统的失败——前者去找客服，后者只会一直重试
        assertTrue(ex.getMessage().contains("已被其他账号使用"), "实际=" + ex.getMessage());
    }
}
