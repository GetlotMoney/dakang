package com.jbk.serve.service.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消息模板注册表锁定（E2E-07 口径7）：纯函数单测，无 Docker 门控——
 * 模板文案是三端可见的契约面，改动必须显式过这里（原先挂在 DbTest 里，
 * 无 Docker 环境会静默跳过，审查纠偏后独立成类）。
 */
class MessageTemplatesTest {

    @Test
    void titlesAreFrozen() {
        assertEquals("报修申请已受理", MessageTemplates.workOrderConfirmed("WO-1", "t").title());
        assertEquals("报修申请未通过", MessageTemplates.workOrderRejected("WO-1", "t", "r").title());
        assertEquals("报修处理完成", MessageTemplates.workOrderClosed("WO-1", "t").title());
    }

    @Test
    void contentCarriesOrderNoAndReason() {
        MessageTemplates.Payload confirmed = MessageTemplates.workOrderConfirmed("WO-1", "维修申报：IT-DEV-0061");
        assertTrue(confirmed.content().contains("WO-1"));
        assertTrue(MessageTemplates.workOrderRejected("WO-2", "t", "非质保范围").content().contains("非质保范围"));
    }

    @Test
    void oversizedBusinessFieldsAreClippedNotThrown() {
        // 业务字段透传超长：收口截断不炸库（标题 100 / 正文 500 与表列宽同源）
        MessageTemplates.Payload longOne = MessageTemplates.workOrderRejected("WO-2", "长".repeat(300), "因".repeat(600));
        assertTrue(longOne.title().length() <= 100);
        assertTrue(longOne.content().length() <= 500);
    }

    @Test
    void contentNeverContainsPhonePattern() {
        for (MessageTemplates.Payload p : new MessageTemplates.Payload[] {
                MessageTemplates.workOrderConfirmed("WO-1", "维修申报：IT-DEV-0061"),
                MessageTemplates.workOrderRejected("WO-2", "配件申报：IT-DEV-0061", "缺货"),
                MessageTemplates.workOrderClosed("WO-3", "维修申报：IT-DEV-0061"),
        }) {
            assertFalse(p.content().matches(".*1[3-9]\\d{9}.*"), "正文禁含手机号形态：" + p.content());
        }
    }

    @Test
    void blankBusinessFieldsFallBackToPlaceholder() {
        assertTrue(MessageTemplates.workOrderConfirmed("WO-1", " ").content().contains("—"));
    }
}
