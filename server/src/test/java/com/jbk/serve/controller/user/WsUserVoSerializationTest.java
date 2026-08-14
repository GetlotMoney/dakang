package com.jbk.serve.controller.user;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.serve.service.user.impl.WsUserServiceImpl;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.user.po.WsCourier;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.data.user.vo.WsUserVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 普通 PC 用户响应不得携带 openid（复审 P1-4），也不得携带明文手机号。
 *
 * <p>①WsUserVo 无 openid 字段；②即使源 PO 带 openid，序列化后的 PC 响应也不含 openid/session_key；
 * ③手机号必须以脱敏形态出接口。</p>
 *
 * <p>第 ③ 条的判据换过两次，两次都是因为它守不住产线：
 * 最早断言 JSON 里**存在**完整号码（把「明文下发」钉成了契约）；改成反向断言后，
 * 被测对象却是测试自己 {@code PhoneMask.mask(...)} 造出来的 VO——{@code assertFalse(含明文)}
 * 对这份夹具恒真，把产线那行脱敏整条删掉它照样绿，实际只证明了 PhoneMask 这个纯函数能工作。
 * 现在的判据从产线读路径取值（{@link WsUserServiceImpl#getData(Long)} → 私有 decorate），
 * 并额外钉住「每条对外返回 WsUserVo 的读路径都必须过 decorate」——
 * 删脱敏那一行、或新开一条直接 copyProperties 返回的读路径，这里都会红。</p>
 */
@DisplayName("PC 用户响应不下发 openid 与明文手机号")
class WsUserVoSerializationTest {

    private static final String RAW_PHONE = "13900001111";

    private static final Path REPO = locateRepoRoot();

    private static final String USER_SERVICE =
            "server/src/main/java/com/jbk/serve/service/user/impl/WsUserServiceImpl.java";

    /** 纯单测无 Spring/Mapper 注册，需手动初始化 MP TableInfo，否则 decorate 里的 LambdaWrapper 取不到 lambda 缓存。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WsDevice.class);
        TableInfoHelper.initTableInfo(assistant, WsStation.class);
        TableInfoHelper.initTableInfo(assistant, WsCourier.class);
    }

    private static Path locateRepoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            if (Files.isDirectory(cur.resolve("client")) && Files.isDirectory(cur.resolve("server"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("无法定位仓库根");
    }

    /** 注释先剥掉：本类的判据全是"源码里有没有这段代码"，被自述性注释命中就成了假绿。 */
    private static String readStripped(String relative) throws IOException {
        return new String(Files.readAllBytes(REPO.resolve(relative)), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    @Test
    @DisplayName("WsUserVo 不声明 openid 字段")
    void wsUserVoHasNoOpenidField() {
        boolean hasOpenid = Arrays.stream(WsUserVo.class.getDeclaredFields())
                .map(Field::getName)
                .anyMatch(n -> n.toLowerCase().contains("openid"));
        assertFalse(hasOpenid, "WsUserVo 不得声明任何 openid 字段");
    }

    @Test
    @DisplayName("源 PO 带 openid 时，序列化后的 PC 响应也不含 openid/session_key")
    void serializedPcUserResponseHasNoOpenid() throws Exception {
        // 源 PO 携带 openid，模拟历史数据；复制到 VO 后 openid 不应被带出。
        WsUser po = new WsUser();
        po.setId(9L);
        po.setUserName("张三");
        po.setUserPhone(RAW_PHONE);
        po.setWechatXcxOpenid("oLEAK-should-not-appear");

        WsUserVo vo = cn.hutool.core.bean.BeanUtil.copyProperties(po, WsUserVo.class);
        String json = new ObjectMapper().writeValueAsString(vo).toLowerCase();

        assertEquals("张三", vo.getUserName());
        assertFalse(json.contains("openid"), "PC 用户响应不得包含 openid");
        assertFalse(json.contains("oleak"), "PC 用户响应不得泄露具体 openid 值");
        assertFalse(json.contains("session_key"), "PC 用户响应不得包含 session_key");
    }

    /**
     * 走产线读路径取值：库里存的是明文，出接口的必须是脱敏串。
     *
     * <p>不连库——{@code WsUserServiceImpl.getData} 只经 baseMapper.selectById 与三张能力表的
     * selectList，全部用 Mockito 桩替；能力查询返回空集合是 Mockito 对 List 返回值的默认行为，
     * 与本判据无关。关键是脱敏那一步真的由产线代码执行，而不是由测试自己先 mask 好再断言。</p>
     */
    @Test
    @DisplayName("产线读路径出接口时手机号已脱敏（删掉 Service 里的脱敏这条会红）")
    void productionReadPathMasksPhoneBeforeItLeavesTheService() throws Exception {
        WsUser po = new WsUser();
        po.setId(9L);
        po.setUserName("张三");
        po.setUserPhone(RAW_PHONE);

        WsUserMapper userMapper = mock(WsUserMapper.class);
        when(userMapper.selectById(9L)).thenReturn(po);

        WsUserServiceImpl service = new WsUserServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
        ReflectionTestUtils.setField(service, "deviceMapper", mock(WsDeviceMapper.class));
        ReflectionTestUtils.setField(service, "stationMapper", mock(WsStationMapper.class));
        ReflectionTestUtils.setField(service, "courierMapper", mock(WsCourierMapper.class));

        WsUserVo vo = service.getData(9L);
        String json = new ObjectMapper().writeValueAsString(vo);

        assertFalse(json.contains(RAW_PHONE),
                "产线读路径把明文手机号带出了接口：" + json);
        assertTrue(json.contains("139****1111"),
                "脱敏号码仍应保留前3后4以便人工核对，实际：" + json);
    }

    /**
     * 脱敏是 decorate 一处做的，而 decorate 是私有方法——挡不住"新写一条读路径直接
     * BeanUtil.copyProperties(po, WsUserVo.class) 返回"。那条新路径同样出接口、同样下发明文，
     * 而上面那个用例只覆盖 getData，不会红。故把"对外返回 WsUserVo 的公开方法必须过 decorate"
     * 也钉住：新增读路径要么走 decorate，要么先回到这里说明它为什么不用脱敏。
     */
    @Test
    @DisplayName("Service 上每条对外返回 WsUserVo 的读路径都过 decorate")
    void everyPublicReadPathGoesThroughDecorate() throws IOException {
        String src = readStripped(USER_SERVICE);
        List<String> methodNames = new ArrayList<>();
        List<String> offenders = new ArrayList<>();

        Matcher m = Pattern.compile("(?m)^\\s+public\\s+([^;{}=()]*\\bWsUserVo\\b[^;{}=()]*?)\\s+(\\w+)\\s*\\(")
                .matcher(src);
        while (m.find()) {
            String name = m.group(2);
            methodNames.add(name);
            if (!bodyOf(src, m.end()).contains("decorate(")) {
                offenders.add(name);
            }
        }

        // 判据自检：一条都没匹配到说明扫描写法失效了（改了返回类型/格式），
        // 那时"没有违规"是假的绿，必须先把扫描修好。
        assertEquals(List.of("getData", "pageData"), methodNames,
                "WsUserServiceImpl 对外返回 WsUserVo 的方法集合变了，先确认新方法是否需要脱敏再更新本清单");
        assertTrue(offenders.isEmpty(),
                "这些读路径没过 decorate，明文手机号会从它们流出去：" + offenders);
    }

    /** 从方法签名后的第一个 '{' 起做花括号配对，取出方法体。 */
    private static String bodyOf(String src, int fromIndex) {
        int open = src.indexOf('{', fromIndex);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open, i + 1);
                }
            }
        }
        return src.substring(open);
    }
}
