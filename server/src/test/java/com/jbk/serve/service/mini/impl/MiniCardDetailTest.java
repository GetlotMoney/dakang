package com.jbk.serve.service.mini.impl;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jbk.serve.mapper.user.WsCardMemberMapper;
import com.jbk.serve.service.user.IWsCardService;
import com.jbk.serve.service.user.IWsUserService;
import com.jbk.tool.data.mini.vo.MiniCardDetailVo;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.data.user.po.WsCardMember;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2-READ 水卡详情：归属 fail-closed、范围描述不粉饰、手机号脱敏。
 */
class MiniCardDetailTest {

    private IWsCardService wsCardService;
    private WsCardMemberMapper wsCardMemberMapper;
    private IWsUserService wsUserService;
    private MiniCardServiceImpl service;

    /** 假库里那一行卡。用例改它来摆布"库里有什么"，让 WHERE 里的归属条件真正参与判定。 */
    private WsCard dbCard;

    private static final Long ME = 9L;
    private static final Long STRANGER = 7L;
    private static final Long CARD = 100L;

    /** MP 3.5.7 的 sqlSegment 形如 {@code (ID = #{ew.paramNameValuePairs.MPGENVAL1} AND USER_ID = #{...})}。 */
    private static final Pattern EQ_COND =
            Pattern.compile("([A-Z_0-9]+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)}");

    /** 纯单测无 Spring/Mapper 注册，需手动初始化 MP TableInfo，否则 LambdaWrapper 生成 SQL 时无 lambda 缓存。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WsCard.class);
    }

    @BeforeEach
    void setup() {
        wsCardService = Mockito.mock(IWsCardService.class);
        wsCardMemberMapper = Mockito.mock(WsCardMemberMapper.class);
        wsUserService = Mockito.mock(IWsUserService.class);
        // CARD-MEMBER 新增身份/订单依赖（详情链不触达，mock 仅为满足构造器）
        service = new MiniCardServiceImpl(wsCardService, wsCardMemberMapper, wsUserService,
                Mockito.mock(com.jbk.serve.mapper.user.WsUserIdentityMapper.class),
                Mockito.mock(com.jbk.serve.mapper.trade.WsOrderMapper.class));
        when(wsCardMemberMapper.selectList(any())).thenReturn(List.of());
        dbCard = card(null, null);
        // 假库：wrapper 的等值条件与库里那一行逐列比对，全中才返回。
        // 直接 thenReturn 会把归属条件整个旁路，删掉 .eq(USER_ID) 测试照样绿。
        when(wsCardService.getOne(any(Wrapper.class))).thenAnswer(inv ->
                dbCard != null && matches(inv.getArgument(0), cardRow(dbCard)) ? dbCard : null);
    }

    /**
     * 把 wrapper 生成的等值条件解析成「列 → 期望值」再与假库那行比对。
     * 遇到假库不认识的列直接炸掉：生产 WHERE 口径漂移必须立刻暴露，而不是被悄悄放行。
     */
    private static boolean matches(Wrapper<?> wrapper, Map<String, Object> row) {
        String segment = wrapper.getSqlSegment();
        Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
        Matcher m = EQ_COND.matcher(segment);
        boolean any = false;
        while (m.find()) {
            any = true;
            String column = m.group(1);
            if (!row.containsKey(column)) {
                throw new IllegalStateException("假库不认识列 " + column + "：" + segment);
            }
            if (!String.valueOf(row.get(column)).equals(String.valueOf(params.get(m.group(2))))) {
                return false;
            }
        }
        if (!any) {
            // 没有任何等值条件 = 查询退化成全表捞，等于没有过滤
            throw new IllegalStateException("查询未产生任何等值条件，判定已失效：" + segment);
        }
        return true;
    }

    private static Map<String, Object> cardRow(WsCard c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", c.getId());
        row.put("USER_ID", c.getUserId());
        return row;
    }

    private WsCard card(String scopeJson, String packageSnap) {
        WsCard c = new WsCard();
        c.setId(CARD);
        c.setUserId(ME);
        c.setCardNo("DK-CARD-001");
        c.setCardType(1);
        c.setCardStatus(1);
        c.setBalanceAmount(5500L);
        c.setBalanceMl(470120L);
        c.setScopeJson(scopeJson);
        c.setPackageSnap(packageSnap);
        return c;
    }

    // 1) 本人卡正常返回，余额字段按契约映射
    @Test
    void ownCardReturnsDetail() {
        dbCard = card(null, null);

        MiniCardDetailVo vo = service.getCardDetail(CARD, ME);

        assertEquals(CARD, vo.getCardId());
        assertEquals(5500L, vo.getBalanceFen());
        assertEquals(470120L, vo.getBalanceMl());
    }

    // 2) 越权核心：归属条件必须写进 WHERE（而不是查出来再判断）
    @Test
    void ownershipIsEnforcedInsideQuery() {
        dbCard = card(null, null);
        service.getCardDetail(CARD, ME);

        ArgumentCaptor<Wrapper<WsCard>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(wsCardService).getOne(cap.capture());
        LambdaQueryWrapper<WsCard> w = (LambdaQueryWrapper<WsCard>) cap.getValue();
        String sql = w.getSqlSegment() + " " + w.getParamNameValuePairs().values();
        assertTrue(sql.contains("USER_ID"), "查询必须包含 USER_ID 归属条件，实际=" + sql);
        assertTrue(w.getParamNameValuePairs().containsValue(ME), "归属条件必须绑定会话登录人");
    }

    // 3) 他人卡/不存在卡：一律拒绝，且不区分二者（不成为探测信道）
    @Test
    void foreignOrMissingCardRejected() {
        dbCard = null;

        JbkException ex = assertThrows(JbkException.class, () -> service.getCardDetail(CARD, ME));
        assertTrue(ex.getMessage().contains("不存在或无权访问"));
    }

    // 4) 参数不完整 fail-closed
    @Test
    void nullArgsRejected() {
        assertThrows(JbkException.class, () -> service.getCardDetail(null, ME));
        assertThrows(JbkException.class, () -> service.getCardDetail(CARD, null));
    }

    // 5) 空范围绝不粉饰为"全场通用"
    @Test
    void blankScopeIsExplicitlyDenied() {
        dbCard = card(null, null);
        assertEquals("未配置（默认拒绝）", service.getCardDetail(CARD, ME).getScopeDescription());

        dbCard = card("{}", null);
        assertEquals("未配置（默认拒绝）", service.getCardDetail(CARD, ME).getScopeDescription());

        // 非法 JSON 同样按未配置处理，不猜测范围
        dbCard = card("not-json", null);
        assertEquals("未配置（默认拒绝）", service.getCardDetail(CARD, ME).getScopeDescription());
    }

    // 6) 显式 scopeType=all 才是全场；白名单给出可读摘要。
    //    CARD-SCOPE 收敛后详情描述与取水/充值同用 WaterCardScope 严格解析：
    //    缺 scopeType 的宽松旧写法不再被描述为限定范围，而是与授权判定同口径回落默认拒绝——
    //    描述与判定不同口径时，用户会在详情页看到「可用」却在取水时被拒。
    @Test
    void scopeDescriptions() {
        dbCard = card("{\"scopeType\":\"all\"}", null);
        assertEquals("全场通用", service.getCardDetail(CARD, ME).getScopeDescription());

        dbCard = card("{\"scopeType\":\"specified\",\"stationIds\":[1,2],\"deviceIds\":[7]}", null);
        String desc = service.getCardDetail(CARD, ME).getScopeDescription();
        assertTrue(desc.contains("水站 2 个"), desc);
        assertTrue(desc.contains("设备 1 台"), desc);

        // 缺 scopeType（历史宽松写法）＝严格解析失败＝默认拒绝，与 allows() 判定保持同一事实
        dbCard = card("{\"stationIds\":[1,2],\"deviceIds\":[7]}", null);
        assertEquals("未配置（默认拒绝）", service.getCardDetail(CARD, ME).getScopeDescription());
    }

    // 7) 套餐名取自卡上快照；快照缺失/非法返回 null
    @Test
    void packageNameFromSnapshot() {
        dbCard = card(null, "{\"packageName\":\"季卡 100L\"}");
        assertEquals("季卡 100L", service.getCardDetail(CARD, ME).getPackageName());

        dbCard = card(null, "broken");
        org.junit.jupiter.api.Assertions.assertNull(service.getCardDetail(CARD, ME).getPackageName());
    }

    // 8) 成员：手机号脱敏、解除态由 enabled 标识、异常号码整体屏蔽不回落明文
    @Test
    void membersAreMaskedAndFlagged() {
        WsCardMember active = new WsCardMember();
        active.setId(1L);
        active.setCardId(CARD);
        active.setMemberUserId(21L);
        active.setMemberName("爸爸");
        active.setMemberStatus(1);
        WsCardMember revoked = new WsCardMember();
        revoked.setId(2L);
        revoked.setCardId(CARD);
        revoked.setMemberUserId(22L);
        revoked.setMemberName("阿姨");
        revoked.setMemberStatus(2);
        when(wsCardMemberMapper.selectList(any())).thenReturn(List.of(active, revoked));

        WsUser u1 = new WsUser();
        u1.setId(21L);
        u1.setUserPhone("13812345678");
        WsUser u2 = new WsUser();
        u2.setId(22L);
        u2.setUserPhone("bad");
        when(wsUserService.list(any(Wrapper.class))).thenReturn(List.of(u1, u2));
        dbCard = card(null, null);

        List<com.jbk.tool.data.mini.vo.MiniCardMemberVo> members = service.getCardDetail(CARD, ME).getMembers();

        assertEquals(2, members.size());
        assertEquals("138****5678", members.get(0).getMaskedPhone());
        assertTrue(members.get(0).getEnabled());
        // 异常号码不回落明文
        assertEquals("", members.get(1).getMaskedPhone());
        assertFalse(members.get(1).getEnabled());
    }

    // 9) 详情响应不得含手机号明文
    @Test
    void detailHasNoRawPhone() throws Exception {
        WsCardMember m = new WsCardMember();
        m.setId(1L);
        m.setCardId(CARD);
        m.setMemberUserId(21L);
        m.setMemberStatus(1);
        when(wsCardMemberMapper.selectList(any())).thenReturn(List.of(m));
        WsUser u = new WsUser();
        u.setId(21L);
        u.setUserPhone("13812345678");
        when(wsUserService.list(any(Wrapper.class))).thenReturn(List.of(u));
        dbCard = card(null, null);

        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(service.getCardDetail(CARD, ME));
        assertFalse(json.contains("13812345678"), "详情不得下发手机号明文：" + json);
    }

    // 10) 卡真实存在但归属他人：必须在 WHERE 层就查不出来。
    //     这条守卫没了，任何登录用户都能拿别人的 cardId 读到余额、有效期与全部授权成员（含脱敏手机号）。
    @Test
    void foreignCardIsInvisibleToQuery() {
        WsCard foreign = card(null, null);
        foreign.setUserId(STRANGER);
        dbCard = foreign;

        JbkException ex = assertThrows(JbkException.class, () -> service.getCardDetail(CARD, ME));
        assertTrue(ex.getMessage().contains("不存在或无权访问"), "实际=" + ex.getMessage());
    }
}
