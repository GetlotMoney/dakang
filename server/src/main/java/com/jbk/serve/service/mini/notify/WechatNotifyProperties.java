package com.jbk.serve.service.mini.notify;

import com.jbk.tool.consts.mini.WechatNotifyEnum;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订阅消息模板配置（wechat.notify.templates.&lt;EventType&gt; = 模板ID）。
 * 模板 ID 随主体/环境各异且可能被停用，只从环境配置读。缺模板不是错误（任务书明写）：
 * 落成 PROCESSED + SKIP_REASON=TEMPLATE_UNCONFIGURED，不发送、不重试、不影响主业务，
 * 记成失败重试会让 Worker 每轮白跑并累积重试计数。
 *
 * @author dakang
 * @since 2026-08-12
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "wechat.notify")
public class WechatNotifyProperties {

    /**
     * 事件类型 → 模板 ID，键用 {@link WechatNotifyEnum.EventType} 的名字。
     * String 键而非绑 enum：绑 enum 时配置写错一个名字=整个应用起不来，而通知只是业务附属品。
     */
    private Map<String, String> templates = new LinkedHashMap<>();

    /**
     * 取某事件的模板 ID；未配置或空串一律返回 null——空串是"占位待填"，
     * 当合法模板发出去微信会回一个与真实原因无关的错误码。
     */
    public String templateOf(WechatNotifyEnum.EventType eventType) {
        if (eventType == null) {
            return null;
        }
        String id = templates.get(eventType.name());
        return (id == null || id.isBlank()) ? null : id;
    }

    /** 已配置的事件集合（供启动自检与运维查看，不含模板 ID 本身）。 */
    public Map<WechatNotifyEnum.EventType, Boolean> configuredMatrix() {
        Map<WechatNotifyEnum.EventType, Boolean> matrix =
                new EnumMap<>(WechatNotifyEnum.EventType.class);
        for (WechatNotifyEnum.EventType type : WechatNotifyEnum.EventType.values()) {
            matrix.put(type, templateOf(type) != null);
        }
        return matrix;
    }
}
