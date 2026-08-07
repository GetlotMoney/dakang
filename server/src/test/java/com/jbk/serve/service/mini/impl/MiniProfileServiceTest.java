package com.jbk.serve.service.mini.impl;

import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.serve.service.mini.IMiniAuthService;
import com.jbk.serve.service.mini.profile.ProfileAvatarStore;
import com.jbk.tool.data.mini.bo.MiniProfileUpdateBo;
import com.jbk.tool.data.mini.vo.MiniAccountContextVo;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 自助资料服务测试：校验矩阵、CAS 语义、头像键派生与「落盘失败不留脏列」。
 * 存储的路径穿越白名单由 {@link ProfileAvatarStore} 自身校验（SAFE_FILE），此处按协作者打桩。
 */
class MiniProfileServiceTest {

    private WsUserIdentityMapper identityMapper;
    private ProfileAvatarStore avatarStore;
    private IMiniAuthService authService;
    private MiniProfileServiceImpl service;

    private static final Long UID = 66L;
    private static final String PNG_B64 = Base64.getEncoder().encodeToString("fake-png".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setup() {
        identityMapper = Mockito.mock(WsUserIdentityMapper.class);
        avatarStore = Mockito.mock(ProfileAvatarStore.class);
        authService = Mockito.mock(IMiniAuthService.class);
        service = new MiniProfileServiceImpl(identityMapper, avatarStore, authService);
        when(authService.currentContext(UID)).thenReturn(new MiniAccountContextVo());
    }

    private MiniProfileUpdateBo bo(String name, String avatarB64, String mime) {
        MiniProfileUpdateBo bo = new MiniProfileUpdateBo();
        bo.setUserName(name);
        bo.setAvatarBase64(avatarB64);
        bo.setAvatarMimeType(mime);
        return bo;
    }

    // 空提交（两者皆缺）→ 拒绝
    @Test
    void rejectsEmptySubmission() {
        assertThrows(JbkException.class, () -> service.updateProfile(UID, bo(null, null, null)));
        assertThrows(JbkException.class, () -> service.updateProfile(UID, bo("   ", null, null)));
        verify(identityMapper, never()).updateNicknameOfUsableUser(anyLong(), anyString(), anyLong(), anyString());
    }

    // 只改昵称：trim 后写库，UPDATE_BY 记本人
    @Test
    void nicknameOnlyUpdate() {
        when(identityMapper.updateNicknameOfUsableUser(eq(UID), eq("新昵称"), eq(UID), anyString())).thenReturn(1);

        MiniAccountContextVo ctx = service.updateProfile(UID, bo("  新昵称  ", null, null));

        assertNotNull(ctx);
        verify(identityMapper).updateNicknameOfUsableUser(eq(UID), eq("新昵称"), eq(UID), anyString());
        verify(avatarStore, never()).store(anyString(), any());
    }

    // 昵称含控制字符 → 拒绝（破坏各端展示）
    @Test
    void nicknameWithControlCharsRejected() {
        assertThrows(JbkException.class, () -> service.updateProfile(UID, bo("abcdef", null, null)));
    }

    // CAS 影响 0 行（账号删除/禁用）→ fail-closed，理由明确
    @Test
    void nicknameCasZeroRowsFailClosed() {
        when(identityMapper.updateNicknameOfUsableUser(eq(UID), anyString(), anyLong(), anyString())).thenReturn(0);

        JbkException ex = assertThrows(JbkException.class, () -> service.updateProfile(UID, bo("名", null, null)));
        assertTrue(ex.getMessage().contains("账号状态异常"), "实际=" + ex.getMessage());
    }

    // 只改头像：键=AV+30位大写hex、扩展名映射 MIME、列写受控相对路径
    @Test
    void avatarOnlyUpdateDerivesControlledFileName() {
        when(identityMapper.updateAvatarOfUsableUser(eq(UID), anyString(), eq(UID), anyString())).thenReturn(1);

        service.updateProfile(UID, bo(null, PNG_B64, "image/png"));

        ArgumentCaptor<String> fileCap = ArgumentCaptor.forClass(String.class);
        verify(avatarStore).store(fileCap.capture(), any());
        assertTrue(fileCap.getValue().matches("^AV[0-9A-F]{30}\\.png$"), "实际=" + fileCap.getValue());
        ArgumentCaptor<String> pathCap = ArgumentCaptor.forClass(String.class);
        verify(identityMapper).updateAvatarOfUsableUser(eq(UID), pathCap.capture(), eq(UID), anyString());
        assertEquals("/mini/profile/avatar/" + fileCap.getValue(), pathCap.getValue());
    }

    // MIME 白名单外 / 非法 base64 / 空内容 → 全部拒绝且不触库
    @Test
    void avatarValidationMatrix() {
        assertThrows(JbkException.class, () -> service.updateProfile(UID, bo(null, PNG_B64, "image/gif")));
        assertThrows(JbkException.class, () -> service.updateProfile(UID, bo(null, "!!not-base64!!", "image/png")));
        assertThrows(JbkException.class, () -> service.updateProfile(UID, bo(null, "", "image/png")));
        verify(identityMapper, never()).updateAvatarOfUsableUser(anyLong(), anyString(), anyLong(), anyString());
    }

    // 头像超 2MB → 拒绝
    @Test
    void avatarOverSizeRejected() {
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        String bigB64 = Base64.getEncoder().encodeToString(big);
        assertThrows(JbkException.class, () -> service.updateProfile(UID, bo(null, bigB64, "image/jpeg")));
    }

    // 落盘抛错 → 异常上抛（事务回滚），列不更新
    @Test
    void storeFailurePreventsColumnUpdate() {
        Mockito.doThrow(new JbkException("头像保存失败")).when(avatarStore).store(anyString(), any());

        assertThrows(JbkException.class, () -> service.updateProfile(UID, bo(null, PNG_B64, "image/png")));
        verify(identityMapper, never()).updateAvatarOfUsableUser(anyLong(), anyString(), anyLong(), anyString());
    }

    // 同人同内容幂等：两次派生同一文件名（内容寻址）
    @Test
    void sameContentSameUserDerivesSameKey() {
        when(identityMapper.updateAvatarOfUsableUser(eq(UID), anyString(), eq(UID), anyString())).thenReturn(1);
        service.updateProfile(UID, bo(null, PNG_B64, "image/png"));
        service.updateProfile(UID, bo(null, PNG_B64, "image/png"));

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(avatarStore, Mockito.times(2)).store(cap.capture(), any());
        assertEquals(cap.getAllValues().get(0), cap.getAllValues().get(1));
    }
}
