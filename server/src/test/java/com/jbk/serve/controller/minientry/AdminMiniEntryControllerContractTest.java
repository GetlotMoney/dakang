package com.jbk.serve.controller.minientry;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 小程序入口管理接口必须使用 Nginx 已反代的 API 前缀，避免与 PC /system 页面前缀冲突。 */
class AdminMiniEntryControllerContractTest {

    @Test
    void adminEndpointsUseApiProxyPrefix() {
        RequestMapping mapping = AdminMiniEntryController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[]{"/api/miniEntry"}, mapping.value());
    }
}
