package com.jbk.tool.interceptor;

import com.jbk.tool.domain.R;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 请求日志脱敏回归：认证票据和个人信息不得再次落入容器日志。 */
class RequestAspectSensitiveLogTest {

    @Test
    void requestBodyRedactsSensitiveFieldsRecursively() {
        String logged = RequestAspect.sanitizeRequestForLog("""
                {"phone":"13900001111","loginPwd":"plain-password","code":"wx-code",
                 "orderNo":"WO-1","nested":{"tokenValue":"jwt-secret","receiveAddress":"home"}}
                """);

        assertTrue(logged.contains("\"orderNo\":\"WO-1\""));
        assertTrue(logged.contains("\"phone\":\"***\""));
        assertTrue(logged.contains("\"tokenValue\":\"***\""));
        assertFalse(logged.contains("13900001111"));
        assertFalse(logged.contains("plain-password"));
        assertFalse(logged.contains("wx-code"));
        assertFalse(logged.contains("jwt-secret"));
        assertFalse(logged.contains("home"));
    }

    @Test
    void malformedBodyNeverFallsBackToRawText() {
        String logged = RequestAspect.sanitizeRequestForLog("phone=13900001111&token=secret");

        assertTrue(logged.contains("omitted"));
        assertFalse(logged.contains("13900001111"));
        assertFalse(logged.contains("secret"));
    }

    @Test
    void responseLogKeepsEnvelopeResultButOmitsPayload() {
        R<Map<String, String>> response = R.ok(Map.of(
                "tokenValue", "jwt-secret",
                "userPhone", "13900001111"));

        String logged = RequestAspect.responseSummaryForLog(response);

        assertTrue(logged.contains("\"code\":0"));
        assertTrue(logged.contains("\"data\":\"<omitted>\""));
        assertFalse(logged.contains("jwt-secret"));
        assertFalse(logged.contains("13900001111"));
    }
}
