package com.jbk.serve.service.mini.impl;

import com.jbk.serve.mapper.user.WsFamilyProfileMapper;
import com.jbk.serve.mapper.user.WsUserAddressMapper;
import com.jbk.tool.data.mini.bo.MiniAddressSaveBo;
import com.jbk.tool.data.mini.bo.MiniFamilySaveBo;
import com.jbk.tool.data.mini.vo.MiniAddressVo;
import com.jbk.tool.data.mini.vo.MiniFamilyProfileVo;
import com.jbk.tool.data.user.po.WsFamilyProfile;
import com.jbk.tool.data.user.po.WsUserAddress;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 家庭资料与地址簿服务测试：归属 fail-closed、脱敏出口、默认位互斥调用序、
 * 隐私同意时间首存不改写、上限守卫。
 */
class MiniFamilyServiceTest {

    private WsUserAddressMapper addressMapper;
    private WsFamilyProfileMapper familyMapper;
    private MiniFamilyServiceImpl service;

    private static final Long UID = 66L;

    @BeforeEach
    void setup() {
        addressMapper = Mockito.mock(WsUserAddressMapper.class);
        familyMapper = Mockito.mock(WsFamilyProfileMapper.class);
        service = new MiniFamilyServiceImpl(addressMapper, familyMapper);
    }

    private WsUserAddress address(Long id, Long userId) {
        WsUserAddress po = new WsUserAddress()
                .setUserId(userId)
                .setContactName("张三")
                .setContactPhone("13912345678")
                .setRegion("湖北武汉")
                .setAddressDetail("光谷软件园 A1")
                .setIsDefault(0)
                .setLocationAuthorized(0);
        po.setId(id);
        return po;
    }

    private MiniAddressSaveBo saveBo() {
        MiniAddressSaveBo bo = new MiniAddressSaveBo();
        bo.setContactName("张三");
        bo.setPhone("13912345678");
        bo.setRegion("湖北武汉");
        bo.setDetail("光谷软件园 A1");
        return bo;
    }

    // 电话出口恒脱敏：VO 绝不吐原号
    @Test
    void addressPhoneAlwaysMasked() {
        when(addressMapper.selectOne(any())).thenReturn(address(1L, UID));

        MiniAddressVo vo = service.getAddress(UID, 1L);

        assertEquals("139****5678", vo.getMaskedPhone());
    }

    // 越权/不存在同响应：selectOne 按归属条件查不到即拒绝（存在性不泄露）
    @Test
    void foreignAddressFailClosed() {
        when(addressMapper.selectOne(any())).thenReturn(null);

        JbkException ex = assertThrows(JbkException.class, () -> service.getAddress(UID, 999L));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    // 设默认：先清同人全部默认位，再落目标行（调用序锁定）
    @Test
    void defaultFlagClearsOthersFirst() {
        MiniAddressSaveBo bo = saveBo();
        bo.setIsDefault(true);
        when(addressMapper.selectCount(any())).thenReturn(0L);

        service.saveAddress(UID, bo);

        InOrder order = inOrder(addressMapper);
        order.verify(addressMapper).clearDefault(eq(UID), eq(UID), anyString());
        ArgumentCaptor<WsUserAddress> cap = ArgumentCaptor.forClass(WsUserAddress.class);
        order.verify(addressMapper).insert(cap.capture());
        assertEquals(1, cap.getValue().getIsDefault());
        assertEquals(UID, cap.getValue().getUserId());
    }

    // 非默认保存不触碰他行默认位
    @Test
    void nonDefaultSaveDoesNotClear() {
        when(addressMapper.selectCount(any())).thenReturn(0L);

        service.saveAddress(UID, saveBo());

        verify(addressMapper, never()).clearDefault(anyLong(), anyLong(), anyString());
    }

    // 地址上限守卫
    @Test
    void addressCountCapEnforced() {
        when(addressMapper.selectCount(any())).thenReturn(20L);

        assertThrows(JbkException.class, () -> service.saveAddress(UID, saveBo()));
        verify(addressMapper, never()).insert(any(WsUserAddress.class));
    }

    // 家庭资料首存必须同意隐私说明
    @Test
    void familyFirstSaveRequiresConsent() {
        when(familyMapper.selectOne(any())).thenReturn(null);
        MiniFamilySaveBo bo = new MiniFamilySaveBo();
        bo.setPrivacyAccepted(false);

        assertThrows(JbkException.class, () -> service.saveFamilyProfile(UID, bo));
        verify(familyMapper, never()).insert(any(WsFamilyProfile.class));
    }

    // 更新不改写同意时间（审计位不可漂移）
    @Test
    void consentTimeImmutableOnUpdate() {
        WsFamilyProfile existing = new WsFamilyProfile()
                .setUserId(UID)
                .setPrivacyConsentTime("20260801000000")
                .setMemberCount(3);
        existing.setId(9L);
        when(familyMapper.selectOne(any())).thenReturn(existing);
        MiniFamilySaveBo bo = new MiniFamilySaveBo();
        bo.setPrivacyAccepted(true);
        bo.setMemberCount(4);

        MiniFamilyProfileVo vo = service.saveFamilyProfile(UID, bo);

        assertEquals("20260801000000", vo.getPrivacyConsentTime());
        assertEquals(4, vo.getMemberCount());
    }

    // 未建档读取返回 null（页面据此渲染建档引导）
    @Test
    void familyProfileNullWhenAbsent() {
        when(familyMapper.selectOne(any())).thenReturn(null);
        assertNull(service.getFamilyProfile(UID));
    }
}
