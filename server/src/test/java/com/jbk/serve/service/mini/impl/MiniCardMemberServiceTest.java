package com.jbk.serve.service.mini.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.user.WsCardMemberMapper;
import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.serve.service.user.IWsCardService;
import com.jbk.serve.service.user.IWsUserService;
import com.jbk.tool.data.mini.bo.MiniCardMemberRevokeBo;
import com.jbk.tool.data.mini.bo.MiniCardMemberSaveBo;
import com.jbk.tool.data.mini.vo.MiniCardMemberVo;
import com.jbk.tool.data.mini.vo.MiniUsableCardVo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.data.user.po.WsCardMember;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CARD-MEMBER 成员授权服务单测：save/revoke 全部拒绝分支、
 * usable-list 角色与能力位、脱敏。写路径副作用全部以 mock 验证零写入。
 */
class MiniCardMemberServiceTest {

    private static final Long OWNER = 9L;
    private static final Long MEMBER_USER = 21L;
    private static final Long CARD = 100L;
    private static final String PHONE = "13812345678";

    private IWsCardService wsCardService;
    private WsCardMemberMapper memberMapper;
    private IWsUserService wsUserService;
    private WsUserIdentityMapper identityMapper;
    private WsOrderMapper orderMapper;
    private MiniCardServiceImpl service;

    /** 假库里的卡：null=查不到（不存在或不属于登录人——归属条件在 WHERE 内）。 */
    private WsCard dbCard;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WsCard.class);
        TableInfoHelper.initTableInfo(assistant, WsCardMember.class);
    }

    @BeforeEach
    void setup() {
        wsCardService = Mockito.mock(IWsCardService.class);
        memberMapper = Mockito.mock(WsCardMemberMapper.class);
        wsUserService = Mockito.mock(IWsUserService.class);
        identityMapper = Mockito.mock(WsUserIdentityMapper.class);
        orderMapper = Mockito.mock(WsOrderMapper.class);
        service = new MiniCardServiceImpl(wsCardService, memberMapper, wsUserService,
                identityMapper, orderMapper);
        dbCard = ownCard();
        when(wsCardService.getOne(any(Wrapper.class))).thenAnswer(inv -> dbCard);
        when(identityMapper.selectByPhoneIncludingDeleted(PHONE)).thenReturn(List.of(usableUser()));
        when(memberMapper.reauthorize(anyLong(), anyLong(), anyString(), any(), any(), any(),
                anyLong(), anyString())).thenReturn(1);
    }

    private WsCard ownCard() {
        WsCard c = new WsCard();
        c.setId(CARD);
        c.setUserId(OWNER);
        c.setCardNo("DK-CARD-001");
        c.setCardType(1);
        c.setCardStatus(1);
        c.setBalanceAmount(5_500L);
        c.setBalanceMl(470_120L);
        return c;
    }

    private WsUser usableUser() {
        WsUser u = new WsUser();
        u.setId(MEMBER_USER);
        u.setUserPhone(PHONE);
        u.setDataStatus(0);
        u.setDisabledFlag(1);
        u.setUserStatus(1);
        return u;
    }

    private MiniCardMemberSaveBo saveBo() {
        MiniCardMemberSaveBo bo = new MiniCardMemberSaveBo();
        bo.setCardId(CARD);
        bo.setMemberName("爸爸");
        bo.setPhone(PHONE);
        return bo;
    }

    private WsCardMember row(Long id, Long memberUserId, int dataStatus, int memberStatus) {
        WsCardMember m = new WsCardMember()
                .setCardId(CARD)
                .setMemberUserId(memberUserId)
                .setMemberName("旧名")
                .setMemberStatus(memberStatus);
        m.setId(id);
        m.setDataStatus(dataStatus);
        return m;
    }

    private void assertNoMemberWrites() {
        verify(memberMapper, never()).insertMember(any());
        verify(memberMapper, never()).reauthorize(anyLong(), anyLong(), anyString(), any(), any(), any(),
                anyLong(), anyString());
        verify(memberMapper, never()).revokeActive(anyLong(), anyLong(), anyLong(), anyString());
    }

    // ==================== save：拒绝分支 ====================

    // 只能管理本人持有的卡：他人卡/不存在卡在 WHERE 层同口径查不出来
    @Test
    void saveRejectsForeignOrMissingCard() {
        dbCard = null;
        JbkException ex = assertThrows(JbkException.class, () -> service.saveMember(saveBo(), OWNER));
        assertTrue(ex.getMessage().contains("不存在或无权访问"), "实际=" + ex.getMessage());
        assertNoMemberWrites();
    }

    // 禁止把自己授权为成员
    @Test
    void saveRejectsSelfAuthorization() {
        WsUser self = usableUser();
        self.setId(OWNER);
        when(identityMapper.selectByPhoneIncludingDeleted(PHONE)).thenReturn(List.of(self));

        JbkException ex = assertThrows(JbkException.class, () -> service.saveMember(saveBo(), OWNER));
        assertTrue(ex.getMessage().contains("不能将本人添加为成员"), "实际=" + ex.getMessage());
        assertNoMemberWrites();
    }

    // 手机号不存在：不自动创建用户，明确拒绝
    @Test
    void saveRejectsUnknownPhoneWithoutCreatingUser() {
        when(identityMapper.selectByPhoneIncludingDeleted(PHONE)).thenReturn(List.of());

        JbkException ex = assertThrows(JbkException.class, () -> service.saveMember(saveBo(), OWNER));
        assertTrue(ex.getMessage().contains("尚未注册"), "实际=" + ex.getMessage());
        verify(identityMapper, never()).insertIdentityUser(any());
        assertNoMemberWrites();
    }

    // 手机号命中多条＝身份污染：fail-closed，不猜测归属
    @Test
    void saveRejectsPollutedPhoneIdentity() {
        when(identityMapper.selectByPhoneIncludingDeleted(PHONE))
                .thenReturn(List.of(usableUser(), usableUser()));

        assertThrows(JbkException.class, () -> service.saveMember(saveBo(), OWNER));
        assertNoMemberWrites();
    }

    // 删除/禁用/注销用户不可被授权（assertUsable 同 L2-AUTH 口径）
    @Test
    void saveRejectsUnusableUser() {
        WsUser deleted = usableUser();
        deleted.setDataStatus(1);
        when(identityMapper.selectByPhoneIncludingDeleted(PHONE)).thenReturn(List.of(deleted));
        assertThrows(JbkException.class, () -> service.saveMember(saveBo(), OWNER));

        WsUser disabled = usableUser();
        disabled.setDisabledFlag(2);
        when(identityMapper.selectByPhoneIncludingDeleted(PHONE)).thenReturn(List.of(disabled));
        assertThrows(JbkException.class, () -> service.saveMember(saveBo(), OWNER));
        assertNoMemberWrites();
    }

    // 时间倒挂：生效时间晚于失效时间
    @Test
    void saveRejectsInvertedWindow() {
        MiniCardMemberSaveBo bo = saveBo();
        bo.setEffectiveTime("20260801000000");
        bo.setExpireTime("20260731000000");

        JbkException ex = assertThrows(JbkException.class, () -> service.saveMember(bo, OWNER));
        assertTrue(ex.getMessage().contains("生效时间不得晚于失效时间"), "实际=" + ex.getMessage());
        assertNoMemberWrites();
    }

    // 伪日期（正则可过、日历非法）严格拒绝
    @Test
    void saveRejectsIllegalCalendarTime() {
        MiniCardMemberSaveBo bo = saveBo();
        bo.setEffectiveTime("20260231120000");

        assertThrows(JbkException.class, () -> service.saveMember(bo, OWNER));
        assertNoMemberWrites();
    }

    // 非正整数限额（0/负数）拒绝；null=不限放行
    @Test
    void saveRejectsNonPositiveDayLimit() {
        MiniCardMemberSaveBo zero = saveBo();
        zero.setDayLimitMl(0L);
        assertThrows(JbkException.class, () -> service.saveMember(zero, OWNER));

        MiniCardMemberSaveBo negative = saveBo();
        negative.setDayLimitMl(-1L);
        assertThrows(JbkException.class, () -> service.saveMember(negative, OWNER));
        assertNoMemberWrites();
    }

    // 编辑不能换人：memberId 定位的记录与手机号解析的用户不一致即拒绝
    @Test
    void editRejectsSwitchingMemberUser() {
        MiniCardMemberSaveBo bo = saveBo();
        bo.setMemberId(1L);
        when(memberMapper.selectByIdIncludingDeleted(1L)).thenReturn(row(1L, 77L, 0, 1));

        JbkException ex = assertThrows(JbkException.class, () -> service.saveMember(bo, OWNER));
        assertTrue(ex.getMessage().contains("编辑不能更换成员"), "实际=" + ex.getMessage());
        assertNoMemberWrites();
    }

    // 编辑：memberId 属于别的卡（越权探测）＝授权成员不存在
    @Test
    void editRejectsMemberIdOfAnotherCard() {
        MiniCardMemberSaveBo bo = saveBo();
        bo.setMemberId(1L);
        WsCardMember foreign = row(1L, MEMBER_USER, 0, 1);
        foreign.setCardId(999L);
        when(memberMapper.selectByIdIncludingDeleted(1L)).thenReturn(foreign);

        JbkException ex = assertThrows(JbkException.class, () -> service.saveMember(bo, OWNER));
        assertTrue(ex.getMessage().contains("授权成员不存在"), "实际=" + ex.getMessage());
        assertNoMemberWrites();
    }

    // ==================== save：成功路径 ====================

    // 新增：插入生效记录，审计字段=卡主，响应只回脱敏号
    @Test
    void saveInsertsNewMemberAndMasksPhone() {
        when(memberMapper.selectByCardAndUserIncludingDeleted(CARD, MEMBER_USER)).thenReturn(null);
        when(memberMapper.insertMember(any())).thenAnswer(inv -> {
            WsCardMember row = inv.getArgument(0);
            row.setId(55L);
            return 1;
        });
        when(memberMapper.selectByIdIncludingDeleted(55L)).thenReturn(row(55L, MEMBER_USER, 0, 1));

        MiniCardMemberVo vo = service.saveMember(saveBo(), OWNER);

        ArgumentCaptor<WsCardMember> cap = ArgumentCaptor.forClass(WsCardMember.class);
        verify(memberMapper).insertMember(cap.capture());
        assertEquals(CARD, cap.getValue().getCardId());
        assertEquals(MEMBER_USER, cap.getValue().getMemberUserId());
        assertEquals(OWNER, cap.getValue().getCreateBy());
        assertEquals("138****5678", vo.getMaskedPhone());
        assertFalse(String.valueOf(vo).contains(PHONE), "响应不得含手机号明文");
    }

    // 重新授权：唯一键命中历史记录（已解除/逻辑删除）时复用原行，不插第二行
    @Test
    void saveReusesExistingRowByUniqueKey() {
        when(memberMapper.selectByCardAndUserIncludingDeleted(CARD, MEMBER_USER))
                .thenReturn(row(55L, MEMBER_USER, 1, 2));
        when(memberMapper.selectByIdIncludingDeleted(55L)).thenReturn(row(55L, MEMBER_USER, 0, 1));

        MiniCardMemberVo vo = service.saveMember(saveBo(), OWNER);

        verify(memberMapper, never()).insertMember(any());
        verify(memberMapper).reauthorize(eq(55L), eq(CARD), eq("爸爸"), isNull(), isNull(), isNull(),
                eq(OWNER), anyString());
        assertTrue(vo.getEnabled());
    }

    // ==================== revoke ====================

    @Test
    void revokeUsesConditionalUpdateAndChecksAffectedRows() {
        MiniCardMemberRevokeBo bo = new MiniCardMemberRevokeBo();
        bo.setCardId(CARD);
        bo.setMemberId(55L);
        when(memberMapper.selectByIdIncludingDeleted(55L))
                .thenReturn(row(55L, MEMBER_USER, 0, 1), row(55L, MEMBER_USER, 0, 2));
        when(memberMapper.revokeActive(eq(55L), eq(CARD), eq(OWNER), anyString())).thenReturn(1);
        when(wsUserService.getById(MEMBER_USER)).thenReturn(usableUser());

        MiniCardMemberVo vo = service.revokeMember(bo, OWNER);

        assertFalse(vo.getEnabled());
        assertEquals("138****5678", vo.getMaskedPhone());
    }

    // 条件更新影响 0 行（并发已解除/状态变化）：明确拒绝，不静默成功
    @Test
    void revokeRejectsWhenNoRowAffected() {
        MiniCardMemberRevokeBo bo = new MiniCardMemberRevokeBo();
        bo.setCardId(CARD);
        bo.setMemberId(55L);
        when(memberMapper.selectByIdIncludingDeleted(55L)).thenReturn(row(55L, MEMBER_USER, 0, 1));
        when(memberMapper.revokeActive(eq(55L), eq(CARD), eq(OWNER), anyString())).thenReturn(0);

        assertThrows(JbkException.class, () -> service.revokeMember(bo, OWNER));
    }

    // 他人卡的 memberId / 已删除记录：一律「授权成员不存在」
    @Test
    void revokeRejectsForeignOrDeletedMember() {
        MiniCardMemberRevokeBo bo = new MiniCardMemberRevokeBo();
        bo.setCardId(CARD);
        bo.setMemberId(55L);
        WsCardMember foreign = row(55L, MEMBER_USER, 0, 1);
        foreign.setCardId(999L);
        when(memberMapper.selectByIdIncludingDeleted(55L)).thenReturn(foreign);
        assertThrows(JbkException.class, () -> service.revokeMember(bo, OWNER));

        when(memberMapper.selectByIdIncludingDeleted(55L)).thenReturn(row(55L, MEMBER_USER, 1, 1));
        assertThrows(JbkException.class, () -> service.revokeMember(bo, OWNER));
        verify(memberMapper, never()).revokeActive(anyLong(), anyLong(), anyLong(), anyString());
    }

    // ==================== usable-list：角色与能力位 ====================

    @Test
    void usableListMarksOwnerAndMemberCapabilities() {
        when(wsCardService.list(any(Wrapper.class))).thenReturn(List.of(ownCard()));
        WsCardMember grant = row(55L, OWNER, 0, 1);
        grant.setCardId(200L);
        grant.setMemberUserId(OWNER);
        grant.setDayLimitMl(10_000L);
        when(memberMapper.selectList(any(Wrapper.class))).thenReturn(List.of(grant));
        WsCard memberCard = ownCard();
        memberCard.setId(200L);
        memberCard.setUserId(77L);
        when(wsCardService.getById(200L)).thenReturn(memberCard);
        when(orderMapper.selectMemberDayWaterOrders(eq(200L), eq(OWNER), anyString(), anyString()))
                .thenReturn(List.of(new WsOrder().setOrderStatus(4).setPlanMl(5_000L).setActualMl(4_000L)));

        List<MiniUsableCardVo> list = service.listUsableCards(OWNER);

        assertEquals(2, list.size());
        MiniUsableCardVo owner = list.get(0);
        assertEquals("OWNER", owner.getAccessRole());
        assertTrue(owner.getCanRecharge());
        assertTrue(owner.getCanManageMembers());
        assertNull(owner.getRemainingDailyLimitMl(), "卡主不返回成员日限额");
        MiniUsableCardVo member = list.get(1);
        assertEquals("MEMBER", member.getAccessRole());
        assertFalse(member.getCanRecharge(), "MEMBER 不得可充值");
        assertFalse(member.getCanManageMembers(), "MEMBER 不得可管成员");
        assertEquals(6_000L, member.getRemainingDailyLimitMl());
    }

    // 赠卡能力位（D-213）：带有效期的本人卡=活动赠卡，不得出现充值入口；其余能力位不变
    @Test
    void usableListDisablesRechargeForGiftCard() {
        WsCard gift = ownCard();
        gift.setExpireTime("20301231235959");
        when(wsCardService.list(any(Wrapper.class))).thenReturn(List.of(gift));
        when(memberMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<MiniUsableCardVo> list = service.listUsableCards(OWNER);

        assertEquals(1, list.size());
        assertFalse(list.get(0).getCanRecharge(), "赠卡不得可充值（付费余额不得被到期日绑架）");
        assertTrue(list.get(0).getCanManageMembers(), "赠卡仍可管成员（只收充值入口）");
    }

    // 无效授权（未生效/已失效/已解除）不出现在列表；成员卡缺失同样跳过
    @Test
    void usableListExcludesInactiveGrants() {
        when(wsCardService.list(any(Wrapper.class))).thenReturn(List.of());
        WsCardMember pending = row(1L, OWNER, 0, 1);
        pending.setCardId(201L);
        pending.setEffectiveTime("29990101000000");
        WsCardMember expired = row(2L, OWNER, 0, 1);
        expired.setCardId(202L);
        expired.setExpireTime("20200101000000");
        WsCardMember missingCard = row(3L, OWNER, 0, 1);
        missingCard.setCardId(203L);
        when(memberMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pending, expired, missingCard));
        when(wsCardService.getById(203L)).thenReturn(null);

        assertEquals(0, service.listUsableCards(OWNER).size());
    }

    // 不限额成员：remainingDailyLimitMl 保持 null（null=不限，不能与 0 混淆）
    @Test
    void usableListKeepsNullForUnlimitedMember() {
        when(wsCardService.list(any(Wrapper.class))).thenReturn(List.of());
        WsCardMember grant = row(55L, OWNER, 0, 1);
        grant.setCardId(200L);
        grant.setDayLimitMl(null);
        when(memberMapper.selectList(any(Wrapper.class))).thenReturn(List.of(grant));
        WsCard memberCard = ownCard();
        memberCard.setId(200L);
        memberCard.setUserId(77L);
        when(wsCardService.getById(200L)).thenReturn(memberCard);

        List<MiniUsableCardVo> list = service.listUsableCards(OWNER);

        assertEquals(1, list.size());
        assertNull(list.get(0).getRemainingDailyLimitMl());
        verify(orderMapper, never()).selectMemberDayWaterOrders(anyLong(), anyLong(), anyString(), anyString());
    }
}
