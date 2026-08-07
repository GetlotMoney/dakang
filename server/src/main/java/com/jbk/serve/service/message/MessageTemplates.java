package com.jbk.serve.service.message;

import cn.hutool.core.util.StrUtil;

/**
 * 站内消息模板注册表（E2E-07 包B / 口径7）：REQ-087「定义消息模板」在 demo 规模下
 * 以代码级注册表满足——模板集中一处、渲染纯函数、单元测试锁定文案，不建模板表。
 *
 * <p>边界：E2E-03 配送域 8 处既有文案已封板在各调用点，【不迁移】进本注册表
 * （迁移=改已验收行为）；自本链起的新增消息一律从这里取文案。
 * 正文禁止拼入手机号等敏感信息（ws_message.MSG_CONTENT DDL 约束口径）。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
public final class MessageTemplates {

    private MessageTemplates() {
    }

    /** 模板渲染结果：标题 ≤100、正文 ≤500（与表列宽同源收口）。 */
    public record Payload(String title, String content) {
    }

    /** 工单受理（待确认→待分配）：告知申报人已进入处理队列。 */
    public static Payload workOrderConfirmed(String orderNo, String orderTitle) {
        return of("报修申请已受理",
                "您的申请「" + brief(orderTitle) + "」已受理（工单号 " + orderNo + "），我们将尽快安排处理。");
    }

    /** 工单驳回（待确认→已驳回）：原因如实带给申报人。 */
    public static Payload workOrderRejected(String orderNo, String orderTitle, String reason) {
        return of("报修申请未通过",
                "您的申请「" + brief(orderTitle) + "」未通过受理（工单号 " + orderNo + "）。原因：" + brief(reason) + "。");
    }

    /** 工单关闭（待复核→已关闭）：处理完成回执。 */
    public static Payload workOrderClosed(String orderNo, String orderTitle) {
        return of("报修处理完成",
                "您的申请「" + brief(orderTitle) + "」已处理完成并复核通过（工单号 " + orderNo + "），感谢您的反馈。");
    }

    private static Payload of(String title, String content) {
        // 收口而非报错：模板自身文案可控，超长只可能来自业务字段透传，截断保证落库不炸
        return new Payload(StrUtil.brief(title, 100), StrUtil.brief(content, 500));
    }

    private static String brief(String text) {
        return StrUtil.brief(StrUtil.blankToDefault(text, "—"), 60);
    }
}
