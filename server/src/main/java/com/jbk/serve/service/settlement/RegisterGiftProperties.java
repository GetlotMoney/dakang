package com.jbk.serve.service.settlement;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 「0 元注册送」赠卡参数（D-418，2026-08-07 甲方确认：注册成功自动发放）。
 *
 * <p><b>默认关闭</b>：发放参数（送多少余额/水量、有效期几天）甲方尚未给出（C-01.8），
 * enabled=false 时注册链零行为。参数到位后改配置并重启后生效（无需代码变更；无热更新机制）；
 * 参数非法（两额皆非正 / 天数越界）时即使开了开关也拒绝发放并记错误日志——
 * 绝不发出一张零权益或超长效期的卡。</p>
 *
 * @author dakang
 * @since 2026-08-07
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "marketing.register-gift")
public class RegisterGiftProperties {

    /** 总开关；默认关闭（D-418 参数未定，C-01.8）。 */
    private boolean enabled = false;

    /** 赠送余额(分)。 */
    private long amountFen = 0L;

    /** 赠送水量(毫升)。 */
    private long waterMl = 0L;

    /** 有效期天数（D-213：赠卡必带有效期，1~3650）。 */
    private int expireDays = 0;
}
