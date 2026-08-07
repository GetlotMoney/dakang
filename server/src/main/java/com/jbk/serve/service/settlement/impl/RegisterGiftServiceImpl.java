package com.jbk.serve.service.settlement.impl;

import com.jbk.serve.service.settlement.IGiftCardService;
import com.jbk.serve.service.settlement.IRegisterGiftService;
import com.jbk.serve.service.settlement.RegisterGiftProperties;
import com.jbk.tool.data.settlement.bo.GiftIssueBo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 「0 元注册送」自动发放（D-418）。
 *
 * <h3>形态</h3>
 * <p>挂在注册建号<b>事务提交之后</b>（登录服务层），复用运营发卡的唯一入口
 * {@link IGiftCardService#issue}——同一套 D-213 校验、发放流水、不可退批次与关键审计，
 * 不另起第二条发卡路径。请求号由 userId 确定性派生（type-3 UUID），同一用户无论
 * 重复触发多少次，卡号锚 {@code uk_card_no} 都只允许发出一张。</p>
 *
 * <h3>失败语义</h3>
 * <p>发放失败<b>不回滚、不阻断注册</b>（注册主链优先）：捕获一切异常记 ERROR 日志后放行。
 * 取舍：newlyCreated 只在建号那一次为 true，失败即漏发、无自动重试——由运营后台
 * 手动发放入口兜底补发（日志含 userId 可查）。要自动重试就得引入发放任务表，
 * 参数未定阶段不做（登记于 D-418）。</p>
 *
 * @author dakang
 * @since 2026-08-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterGiftServiceImpl implements IRegisterGiftService {

    /** 系统发放的操作人哨兵（与平台分账行 0 哨兵同语义：非自然人动作）。 */
    private static final long SYSTEM_OPERATOR = 0L;

    private final RegisterGiftProperties properties;
    private final IGiftCardService giftCardService;

    @Override
    public void grantIfEnabled(Long userId) {
        if (!properties.isEnabled() || userId == null) {
            return;
        }
        // 「注册事务提交后触发」目前靠调用栈约定成立（登录链无事务）。这道断言把约定钉成机制：
        // 将来任何调用方把本方法包进活动事务，catch 包 REQUIRED 的 issue 会踩
        // UnexpectedRollbackException、且「提交后」语义失效——发现即跳过（漏发可由运营补发，
        // 比在别人事务里埋回滚坑便宜得多）并留告警。
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            log.warn("注册送发放在活动事务内被调用，跳过（应在注册事务提交后触发）：userId={}", userId);
            return;
        }
        long fen = properties.getAmountFen();
        long ml = properties.getWaterMl();
        int days = properties.getExpireDays();
        // 与 GiftCardServiceImpl.issue 的参数校验完全同构（审查维度4）：任一为负或两者皆零
        // 都走「参数非法」日志——不同构会让负值配置穿透预检、在 issue 处按「发放失败」误导补发
        if (fen < 0 || ml < 0 || (fen == 0 && ml == 0) || days < 1 || days > 3650) {
            // 开关开了但参数缺失/非法：拒绝发放并留错误日志——绝不发零权益或非法效期的卡
            log.error("注册送赠卡参数非法，跳过发放：userId={} fen={} ml={} days={}", userId, fen, ml, days);
            return;
        }
        // type-3 UUID：同一 userId 恒同请求号 → 恒同卡号 → uk_card_no 保证终身至多一张
        String requestId = UUID.nameUUIDFromBytes(
                ("REG-GIFT:" + userId).getBytes(StandardCharsets.UTF_8)).toString();
        try {
            GiftIssueBo bo = new GiftIssueBo();
            bo.setRequestId(requestId);
            bo.setUserId(userId);
            bo.setGrantFen(fen);
            bo.setGrantMl(ml);
            bo.setExpireDays(days);
            bo.setRemark("注册自动发放（D-418）");
            Long cardId = giftCardService.issue(bo, SYSTEM_OPERATOR);
            log.info("注册送赠卡发放成功：userId={} cardId={} fen={} ml={} days={}",
                    userId, cardId, fen, ml, days);
        } catch (Exception failed) {
            // 分型（审查维度2 P3）：「已发放」类冲突的事实是卡已存在——此时按补发指引操作
            // 恰好双发。按消息关键词分流只影响日志文案（不影响控制流），可接受的脆弱面。
            if (failed.getMessage() != null && failed.getMessage().contains("已发放")) {
                log.error("注册送赠卡重放核验不符（该用户已有发放记录，勿直接补发，请先核查既有卡）："
                        + "userId={}", userId, failed);
            } else {
                log.error("注册送赠卡发放失败（注册不受影响，请运营按 userId 手动补发）：userId={}", userId, failed);
            }
        }
    }
}
