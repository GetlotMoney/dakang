package com.jbk.serve.service.delivery.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.delivery.WsDeliveryAutoRuleMapper;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.mapper.product.WsWaterTypeMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.delivery.DeliveryClock;
import com.jbk.serve.service.delivery.DeliveryOrderNo;
import com.jbk.serve.service.delivery.IMiniAutoRuleService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.delivery.po.WsDeliveryAutoRule;
import com.jbk.tool.data.mini.vo.MiniAutoRuleVo;
import com.jbk.tool.data.ops.po.WsDomainEvent;
import com.jbk.tool.data.product.po.WsWaterType;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 自动补货规则用户自助管理实现（S2）。最近执行结果取两条证据线：成功=期次订单本身
 * （订单号确定性派生逐期反查，最多回看 {@link #RESULT_LOOKBACK_PERIODS} 期）；
 * 失败=生成器领域事件（{@code AUTO_REFILL_FAIL:<ruleId>:<period>}）。
 * 两线各取最新、再取较近者展示，不新建执行记录表（第二套账本只会漂移）。
 */
@Slf4j
@Service
public class MiniAutoRuleServiceImpl implements IMiniAutoRuleService {

    /** 最近结果回看期数：展示用途，3 期足够覆盖「上次成功/最近连续失败」的判读。 */
    private static final int RESULT_LOOKBACK_PERIODS = 3;

    private static final String FAIL_KEY_PREFIX = "AUTO_REFILL_FAIL:";

    @Autowired
    private WsDeliveryAutoRuleMapper autoRuleMapper;
    @Autowired
    private WsWaterTypeMapper waterTypeMapper;
    @Autowired
    private WsOrderMapper orderMapper;
    @Autowired
    private WsDomainEventMapper domainEventMapper;
    @Autowired
    private IWsDomainEventService domainEventService;

    @Override
    public List<MiniAutoRuleVo> listMine(Long userId) {
        requireUserId(userId);
        // 数据范围铁律：Service 层按会话主体过滤，绝不接受前端圈定
        List<WsDeliveryAutoRule> rules = autoRuleMapper.selectList(
                Wrappers.lambdaQuery(WsDeliveryAutoRule.class)
                        .eq(WsDeliveryAutoRule::getUserId, userId)
                        .orderByDesc(WsDeliveryAutoRule::getId));
        if (rules.isEmpty()) {
            return List.of();
        }
        Map<Long, String> waterNames = waterTypeMapper.selectBatchIds(
                        rules.stream().map(WsDeliveryAutoRule::getWaterTypeId).distinct().toList())
                .stream().collect(Collectors.toMap(WsWaterType::getId, WsWaterType::getWaterName,
                        (a, b) -> a));
        String now = DateUtils.time();
        return rules.stream().map(rule -> toVo(rule, waterNames, now)).toList();
    }

    private MiniAutoRuleVo toVo(WsDeliveryAutoRule rule, Map<Long, String> waterNames, String now) {
        MiniAutoRuleVo vo = new MiniAutoRuleVo()
                .setId(rule.getId())
                .setWaterTypeName(waterNames.getOrDefault(rule.getWaterTypeId(), "已下架水种"))
                .setContainerSpec(rule.getContainerSpec())
                .setDeliveryCount(rule.getDeliveryCount())
                .setReceiveAddress(rule.getReceiveAddress())
                .setIntervalDays(rule.getIntervalDays())
                .setRuleStatus(rule.getRuleStatus());
        if (ObjectUtil.equal(rule.getRuleStatus(), DeliveryEnum.AutoRuleStatus.ENABLED.getValue())) {
            long currentPeriod = Math.max(0, DeliveryClock.periodIndex(
                    rule.getAnchorTime(), now, rule.getIntervalDays()));
            long nextDueSeconds = (currentPeriod + 1) * rule.getIntervalDays() * 86_400L;
            vo.setNextDueTime(DateUtils.plusSeconds(rule.getAnchorTime(), nextDueSeconds));
        }
        fillLastResult(vo, rule, now);
        return vo;
    }

    /** 最近执行结果：成功（期次订单）与失败（留痕事件）两线取时间较近者。 */
    private void fillLastResult(MiniAutoRuleVo vo, WsDeliveryAutoRule rule, String now) {
        long currentPeriod = DeliveryClock.periodIndex(rule.getAnchorTime(), now, rule.getIntervalDays());
        String successResult = null;
        String successTime = null;
        for (long p = currentPeriod; p >= 1 && p > currentPeriod - RESULT_LOOKBACK_PERIODS; p--) {
            String orderNo = DeliveryOrderNo.deriveAutoRefill(rule.getUserId(), rule.getId(), p);
            WsOrder order = orderMapper.selectOne(Wrappers.lambdaQuery(WsOrder.class)
                    .eq(WsOrder::getOrderNo, orderNo));
            if (ObjectUtil.isNotNull(order)) {
                successResult = "第" + p + "期已生成";
                successTime = order.getCreateTime();
                break;
            }
        }
        WsDomainEvent lastFail = domainEventMapper.selectOne(Wrappers.lambdaQuery(WsDomainEvent.class)
                .likeRight(WsDomainEvent::getBizIdempotencyKey, FAIL_KEY_PREFIX + rule.getId() + ":")
                .orderByDesc(WsDomainEvent::getId)
                .last("LIMIT 1"));
        String failResult = null;
        String failTime = null;
        if (ObjectUtil.isNotNull(lastFail)) {
            failTime = lastFail.getCreateTime();
            failResult = parseFailReason(lastFail.getEventPayload());
        }
        if (successTime != null && (failTime == null || successTime.compareTo(failTime) >= 0)) {
            vo.setLastResult(successResult).setLastResultTime(successTime);
        }
        else if (failTime != null) {
            vo.setLastResult(failResult).setLastResultTime(failTime);
        }
    }

    /** 失败事件 payload 形如 {"old":null,"new":"<原因文案>","time":...}；解析失败降级为通用文案。 */
    private String parseFailReason(String payload) {
        try {
            String reason = JSONUtil.parseObj(payload).getStr("new");
            return ObjectUtil.isNotNull(reason) && !reason.isBlank() ? reason : "本期未生成";
        }
        catch (Exception e) {
            return "本期未生成";
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pause(Long userId, Long ruleId) {
        transition(userId, ruleId,
                new int[] { DeliveryEnum.AutoRuleStatus.ENABLED.getValue() },
                DeliveryEnum.AutoRuleStatus.DISABLED.getValue(), "暂停");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resume(Long userId, Long ruleId) {
        // 前态严格=停用：已取消(3)永不匹配，终态不可恢复由前态收窄天然保证
        transition(userId, ruleId,
                new int[] { DeliveryEnum.AutoRuleStatus.DISABLED.getValue() },
                DeliveryEnum.AutoRuleStatus.ENABLED.getValue(), "恢复");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long ruleId) {
        transition(userId, ruleId,
                new int[] { DeliveryEnum.AutoRuleStatus.ENABLED.getValue(),
                        DeliveryEnum.AutoRuleStatus.DISABLED.getValue() },
                DeliveryEnum.AutoRuleStatus.CANCELLED.getValue(), "取消");
    }

    /**
     * 三键精确前态 CAS：ID+本人+合法前态。影响 0 行统一报「规则不存在」——
     * 他人规则直达、重复操作、非法前态（如恢复已取消）对外同一形状，不泄露存在性。
     * 与 Worker 并发：期次创单事务锁定复核规则行，本 CAS 先提交则该期 fail-closed。
     */
    private void transition(Long userId, Long ruleId, int[] fromStatuses, int toStatus, String action) {
        requireUserId(userId);
        if (ruleId == null || ruleId <= 0) {
            throw new JbkException("规则不存在");
        }
        List<Integer> from = java.util.Arrays.stream(fromStatuses).boxed().toList();
        int updated = autoRuleMapper.update(null, Wrappers.lambdaUpdate(WsDeliveryAutoRule.class)
                .eq(WsDeliveryAutoRule::getId, ruleId)
                .eq(WsDeliveryAutoRule::getUserId, userId)
                .in(WsDeliveryAutoRule::getRuleStatus, from)
                .set(WsDeliveryAutoRule::getRuleStatus, toStatus));
        if (updated != 1) {
            throw new JbkException("规则不存在或当前状态不支持" + action);
        }
        // 成功状态审计与 CAS 同事务（REQUIRED）：CAS 回滚审计随之消失，无幽灵记录；
        // 暂停⇄恢复可循环发生，无幂等键每次流转各成一行，互不撞键
        domainEventService.recordReliableInTx(OpsEnum.EventType.ORDER_STATUS,
                "AUTO_RULE:" + ruleId, from, toStatus);
    }

    @Override
    public com.jbk.tool.data.PageDataVo<com.jbk.tool.data.delivery.vo.AdminAutoRuleVo> pageForAdmin(
            Long userId, Integer ruleStatus, long current, long size) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WsDeliveryAutoRule> page =
                autoRuleMapper.selectPage(
                        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                                Math.max(1, current), Math.min(Math.max(1, size), 100)),
                        Wrappers.lambdaQuery(WsDeliveryAutoRule.class)
                                .eq(userId != null, WsDeliveryAutoRule::getUserId, userId)
                                .eq(ruleStatus != null, WsDeliveryAutoRule::getRuleStatus, ruleStatus)
                                .orderByDesc(WsDeliveryAutoRule::getId));
        List<com.jbk.tool.data.delivery.vo.AdminAutoRuleVo> rows = page.getRecords().stream()
                .map(rule -> new com.jbk.tool.data.delivery.vo.AdminAutoRuleVo()
                        .setId(rule.getId())
                        .setUserId(rule.getUserId())
                        .setContainerSpec(rule.getContainerSpec())
                        .setDeliveryCount(rule.getDeliveryCount())
                        .setIntervalDays(rule.getIntervalDays())
                        .setAnchorTime(rule.getAnchorTime())
                        .setRuleStatus(rule.getRuleStatus())
                        .setReceiveAddress(rule.getReceiveAddress())
                        // 脱敏唯一实现：非 11 位整体屏蔽，绝不回落明文
                        .setMaskedPhone(com.jbk.tool.utils.PhoneMask.mask(rule.getReceivePhone()))
                        .setCreateTime(rule.getCreateTime()))
                .toList();
        return new com.jbk.tool.data.PageDataVo<>(rows, page.getTotal());
    }

    private static void requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new JbkException("登录状态异常");
        }
    }
}
