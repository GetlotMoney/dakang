package com.jbk.serve.service.mini.impl;

import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.user.WsCardMapper;
import com.jbk.serve.mapper.user.WsCardMemberMapper;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.mini.vo.WaterEligibilityVo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.data.user.po.WsCardMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * U04 资格预检的指定卡语义（CARD-SCOPE）：后端只检查请求指明的 cardId——
 * 「预检卡A、下单卡B」的口子由此封死（下单事务内仍二次校验，预检不是安全边界）。
 * 重点钉三类新阻断：他人卡/不存在卡统一 CARD_NOT_ACCESSIBLE（不泄露存在性）、
 * 非法范围 CARD_SCOPE_INVALID、范围未命中 CARD_SCOPE_DENIED。
 * CARD-MEMBER：卡可及性扩为「卡主或有效成员」——有效成员放行并回日剩余额度，
 * 无关用户/无效授权仍与不存在卡同码同文案。
 */
class MiniWaterEligibilityCardTest {

    private static final Long USER_ID = 9L;
    private static final Long OWNER_ID = 7L;
    private static final Long CARD_ID = 100L;
    private static final String SESSION_ID = "scan-session-x";

    private MiniDeviceServiceImpl service;
    private WsCardMapper cardMapper;
    private WsCardMemberMapper cardMemberMapper;
    private WsOrderMapper wsOrderMapper;
    private WsDeviceMapper deviceMapper;
    private WsDeviceOutletMapper outletMapper;
    private com.jbk.serve.service.ops.IWsDomainEventService domainEventService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        service = new MiniDeviceServiceImpl();
        cardMapper = Mockito.mock(WsCardMapper.class);
        cardMemberMapper = Mockito.mock(WsCardMemberMapper.class);
        wsOrderMapper = Mockito.mock(WsOrderMapper.class);
        deviceMapper = Mockito.mock(WsDeviceMapper.class);
        outletMapper = Mockito.mock(WsDeviceOutletMapper.class);
        RedisTemplate<String, Object> redis = Mockito.mock(RedisTemplate.class);
        ValueOperations<String, Object> ops = Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        // 会话归属本人、绑定 41 站 / 21 机 / 31 口（与 resolve 铸造的结构一致）
        when(ops.get("mini:scan:" + SESSION_ID)).thenReturn(
                "{\"userId\":9,\"qrcodeId\":11,\"stationId\":41,\"deviceId\":21,"
                        + "\"deviceNo\":\"DK-DEV-0021\",\"outletId\":31}");
        ReflectionTestUtils.setField(service, "cardMapper", cardMapper);
        // CARD-MEMBER：成员判定与日限额估算的新依赖，同样以 mock 注入
        ReflectionTestUtils.setField(service, "cardMemberMapper", cardMemberMapper);
        ReflectionTestUtils.setField(service, "wsOrderMapper", wsOrderMapper);
        ReflectionTestUtils.setField(service, "deviceMapper", deviceMapper);
        ReflectionTestUtils.setField(service, "outletMapper", outletMapper);
        domainEventService = Mockito.mock(com.jbk.serve.service.ops.IWsDomainEventService.class);
        ReflectionTestUtils.setField(service, "domainEventService", domainEventService);
        ReflectionTestUtils.setField(service, "redis", redis);
        when(deviceMapper.selectById(21L)).thenReturn(
                new WsDevice().setId(21L).setStationId(41L).setOnlineStatus(1).setRunStatus(1));
        when(outletMapper.selectById(31L)).thenReturn(
                new WsDeviceOutlet().setId(31L).setDeviceId(21L).setOutletStatus(1));
    }

    private WsCard card(int status, String scope) {
        WsCard c = new WsCard();
        c.setId(CARD_ID);
        c.setUserId(USER_ID);
        c.setCardStatus(status);
        c.setBalanceMl(12_345L);
        c.setScopeJson(scope);
        return c;
    }

    private WaterEligibilityVo check(Long cardId) {
        return service.checkEligibility(SESSION_ID, cardId, USER_ID);
    }

    // 未指明 cardId：按未持卡阻断（前端无主卡时不传）
    @Test
    void missingCardIdYieldsCardMissing() {
        assertEquals("CARD_MISSING", check(null).getCardBlock().getCode());
    }

    // 他人卡与不存在卡必须同码 CARD_NOT_ACCESSIBLE——两个分支返回一旦可区分，就成了探测他人 cardId 的信道
    @Test
    void foreignAndAbsentCardsAreIndistinguishable() {
        when(cardMapper.selectById(CARD_ID)).thenReturn(null);
        WaterEligibilityVo absent = check(CARD_ID);

        WsCard foreign = card(1, "{\"scopeType\":\"all\"}");
        foreign.setUserId(7L);
        when(cardMapper.selectById(CARD_ID)).thenReturn(foreign);
        WaterEligibilityVo other = check(CARD_ID);

        assertEquals("CARD_NOT_ACCESSIBLE", absent.getCardBlock().getCode());
        assertEquals("CARD_NOT_ACCESSIBLE", other.getCardBlock().getCode());
        assertEquals(absent.getCardBlock().getMessage(), other.getCardBlock().getMessage(),
                "两分支文案必须完全一致，不得泄露卡是否存在");
    }

    // 状态阻断沿用既有码
    @Test
    void statusBlocksKeepDedicatedCodes() {
        when(cardMapper.selectById(CARD_ID)).thenReturn(card(2, "{\"scopeType\":\"all\"}"));
        assertEquals("CARD_FROZEN", check(CARD_ID).getCardBlock().getCode());

        when(cardMapper.selectById(CARD_ID)).thenReturn(card(4, "{\"scopeType\":\"all\"}"));
        assertEquals("CARD_CANCELLED", check(CARD_ID).getCardBlock().getCode());

        when(cardMapper.selectById(CARD_ID)).thenReturn(card(3, "{\"scopeType\":\"all\"}"));
        assertEquals("CARD_EXPIRED", check(CARD_ID).getCardBlock().getCode());
    }

    // 空/非法范围＝SCOPE_INVALID；合法但未命中本站-设备-出水口＝SCOPE_DENIED
    @Test
    void scopeInvalidAndScopeDeniedAreDistinguished() {
        when(cardMapper.selectById(CARD_ID)).thenReturn(card(1, null));
        assertEquals("CARD_SCOPE_INVALID", check(CARD_ID).getCardBlock().getCode());

        when(cardMapper.selectById(CARD_ID)).thenReturn(card(1, "not-json"));
        assertEquals("CARD_SCOPE_INVALID", check(CARD_ID).getCardBlock().getCode());

        // AND 证伪（预检面）：站 41 命中但设备名单只有 99 → 必须 DENIED
        when(cardMapper.selectById(CARD_ID)).thenReturn(card(1,
                "{\"scopeType\":\"specified\",\"stationIds\":[41],\"deviceIds\":[99]}"));
        assertEquals("CARD_SCOPE_DENIED", check(CARD_ID).getCardBlock().getCode());
    }

    // P1-C 边界：范围拒绝审计只在真实下单事务内落痕——预检面的 INVALID/DENIED 绝不写任何领域事件
    @Test
    void scopeRejectionAtPrecheckWritesNoAuditEvent() {
        when(cardMapper.selectById(CARD_ID)).thenReturn(card(1, null));
        assertEquals("CARD_SCOPE_INVALID", check(CARD_ID).getCardBlock().getCode());

        when(cardMapper.selectById(CARD_ID)).thenReturn(card(1,
                "{\"scopeType\":\"specified\",\"stationIds\":[99]}"));
        assertEquals("CARD_SCOPE_DENIED", check(CARD_ID).getCardBlock().getCode());

        Mockito.verifyNoInteractions(domainEventService);
    }

    // 全通过（卡主）：无阻断，maxAllowedMl=剩余水量（仅水量支付参考），卡主不返回日限额
    @Test
    void eligibleCardExposesMlReferenceOnly() {
        when(cardMapper.selectById(CARD_ID)).thenReturn(card(1,
                "{\"scopeType\":\"specified\",\"stationIds\":[41]}"));

        WaterEligibilityVo vo = check(CARD_ID);

        assertNull(vo.getCardBlock());
        assertEquals(12_345L, vo.getMaxAllowedMl());
        assertNull(vo.getRemainingDailyLimitMl(), "卡主取水不返回成员日限额");
    }

    // ==================== CARD-MEMBER：成员卡可及性 ====================

    private WsCard ownerCard() {
        WsCard c = card(1, "{\"scopeType\":\"specified\",\"stationIds\":[41]}");
        c.setUserId(OWNER_ID);
        return c;
    }

    private WsCardMember grant(int dataStatus, int memberStatus, String effective, String expire, Long dayLimit) {
        WsCardMember m = new WsCardMember()
                .setCardId(CARD_ID)
                .setMemberUserId(USER_ID)
                .setDayLimitMl(dayLimit)
                .setEffectiveTime(effective)
                .setExpireTime(expire)
                .setMemberStatus(memberStatus);
        m.setDataStatus(dataStatus);
        return m;
    }

    // 有效成员：放行，并返回「限额-当日占用」的剩余额度（估算口径同 MemberDayLimitMath）
    @Test
    void activeMemberPassesAndSeesRemainingDailyLimit() {
        when(cardMapper.selectById(CARD_ID)).thenReturn(ownerCard());
        when(cardMemberMapper.selectByCardAndUserIncludingDeleted(CARD_ID, USER_ID))
                .thenReturn(grant(0, 1, null, null, 10_000L));
        // 当天已占用：已完成 3000ml（实际）+ 已支付 2000ml（计划）
        when(wsOrderMapper.selectMemberDayWaterOrders(eq(CARD_ID), eq(USER_ID), anyString(), anyString()))
                .thenReturn(List.of(
                        new WsOrder().setOrderStatus(4).setPlanMl(5_000L).setActualMl(3_000L),
                        new WsOrder().setOrderStatus(2).setPlanMl(2_000L)));

        WaterEligibilityVo vo = check(CARD_ID);

        assertNull(vo.getCardBlock());
        assertEquals(12_345L, vo.getMaxAllowedMl());
        assertEquals(5_000L, vo.getRemainingDailyLimitMl());
    }

    // 不限额成员：放行且日限额保持 null（null=不限，绝不能与 0 混淆）
    @Test
    void unlimitedMemberKeepsNullRemaining() {
        when(cardMapper.selectById(CARD_ID)).thenReturn(ownerCard());
        when(cardMemberMapper.selectByCardAndUserIncludingDeleted(CARD_ID, USER_ID))
                .thenReturn(grant(0, 1, null, null, null));

        WaterEligibilityVo vo = check(CARD_ID);

        assertNull(vo.getCardBlock());
        assertNull(vo.getRemainingDailyLimitMl());
    }

    // 无关用户（无授权）与撤销/删除/未生效/已失效授权：全部与「卡不存在」同码同文案
    @Test
    void invalidMembershipsAreIndistinguishableFromAbsentCard() {
        when(cardMapper.selectById(CARD_ID)).thenReturn(null);
        WaterEligibilityVo absent = check(CARD_ID);
        assertEquals("CARD_NOT_ACCESSIBLE", absent.getCardBlock().getCode());

        when(cardMapper.selectById(CARD_ID)).thenReturn(ownerCard());
        WsCardMember[] invalidGrants = {
                null,
                grant(0, 2, null, null, null),
                grant(1, 1, null, null, null),
                grant(0, 1, "29990101000000", null, null),
                grant(0, 1, null, "20200101000000", null),
        };
        for (WsCardMember g : invalidGrants) {
            when(cardMemberMapper.selectByCardAndUserIncludingDeleted(CARD_ID, USER_ID)).thenReturn(g);
            WaterEligibilityVo vo = check(CARD_ID);
            assertEquals("CARD_NOT_ACCESSIBLE", vo.getCardBlock().getCode());
            assertEquals(absent.getCardBlock().getMessage(), vo.getCardBlock().getMessage(),
                    "无效成员授权与不存在卡的文案必须完全一致，不得泄露授权历史");
        }
    }
}
