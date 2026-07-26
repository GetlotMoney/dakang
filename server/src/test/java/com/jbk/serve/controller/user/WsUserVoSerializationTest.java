package com.jbk.serve.controller.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.data.user.vo.WsUserVo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 普通 PC 用户响应不得携带 openid（复审 P1-4）。
 *
 * <p>①WsUserVo 无 openid 字段；②即使源 PO 带 openid，序列化后的 PC 响应也不含 openid/session_key。</p>
 */
class WsUserVoSerializationTest {

    @Test
    void wsUserVoHasNoOpenidField() {
        boolean hasOpenid = Arrays.stream(WsUserVo.class.getDeclaredFields())
                .map(Field::getName)
                .anyMatch(n -> n.toLowerCase().contains("openid"));
        assertFalse(hasOpenid, "WsUserVo 不得声明任何 openid 字段");
    }

    @Test
    void serializedPcUserResponseHasNoOpenid() throws Exception {
        // 源 PO 携带 openid，模拟历史数据；复制到 VO 后 openid 不应被带出。
        WsUser po = new WsUser();
        po.setId(9L);
        po.setUserName("张三");
        po.setUserPhone("13900001111");
        po.setWechatXcxOpenid("oLEAK-should-not-appear");

        WsUserVo vo = cn.hutool.core.bean.BeanUtil.copyProperties(po, WsUserVo.class);
        String json = new ObjectMapper().writeValueAsString(vo).toLowerCase();

        assertEquals("张三", vo.getUserName());
        assertFalse(json.contains("openid"), "PC 用户响应不得包含 openid");
        assertFalse(json.contains("oleak"), "PC 用户响应不得泄露具体 openid 值");
        assertFalse(json.contains("session_key"), "PC 用户响应不得包含 session_key");
        assertTrue(json.contains("13900001111"), "手机号等常规字段仍应存在");
    }
}
