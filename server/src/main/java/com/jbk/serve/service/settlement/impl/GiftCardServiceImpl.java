package com.jbk.serve.service.settlement.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCardMapper;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.serve.service.aftersale.batch.EntitlementBatchWriter;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.settlement.IGiftCardService;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.settlement.bo.GiftIssueBo;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.OptionalUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Calendar;

/**
 * 运营赠卡发放（E2E-08 包D / D-213 现行口径：赠卡带有效期、不可充值、消费不受限、
 * 恒不可退、不占一人一卡名额）。
 *
 * <p>幂等锚：卡号由请求号确定性派生（GC+SHA256(requestId)），撞 uk_card_no 即同一
 * 请求重放——读回既有卡返回，绝不发第二张（赠卡无 ISSUE_ORDER_ID 订单锚，卡号即锚）。
 * 同事务：建卡 + GIFT 流水 + 不可退批次 + 关键审计，任一失败整体回滚。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
@Slf4j
@Service
public class GiftCardServiceImpl implements IGiftCardService {

    @Autowired
    private WsCardMapper cardMapper;
    @Autowired
    private WsUserMapper userMapper;
    @Autowired
    private WsWalletFlowMapper walletFlowMapper;
    @Autowired
    private WsCardEntitlementBatchMapper batchMapper;
    @Autowired
    private IWsDomainEventService domainEventService;

    @Override
    /**
     * READ_COMMITTED（R1 P1-1，与 RechargeIssueTxImpl N-15 / RechargeCreditTxImpl 同因同解）：
     * REPEATABLE READ 下方法开头的 userMapper.selectById 已建立一致性读视图，撞 uk_card_no
     * 的并发败方即使把卡查询改成锁定读，随后的 GIFT 流水与发放批次仍是旧快照——独立复审
     * 已实测 locking_card=1 而 snapshot_flow=0：合法并发重放会因看不到胜方流水被误判
     * 「参数不符/冲突」。READ_COMMITTED 让卡（锁定读）、流水、批次三项重放证据同处
     * 「最新已提交」视野，两个并发合法重放都收敛到同一 cardId。
     */
    @Transactional(rollbackFor = Exception.class,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Long issue(GiftIssueBo bo, Long operatorId) {
        String requestId = StrUtil.trimToEmpty(bo.getRequestId());
        if (!requestId.matches("^[0-9a-fA-F-]{36}$")) {
            throw new JbkException("赠卡请求号必须是规范 UUID");
        }
        long grantFen = ObjectUtil.defaultIfNull(bo.getGrantFen(), 0L);
        long grantMl = ObjectUtil.defaultIfNull(bo.getGrantMl(), 0L);
        if (grantFen < 0 || grantMl < 0 || (grantFen == 0 && grantMl == 0)) {
            throw new JbkException("赠卡权益必须为正（余额或水量至少其一）");
        }
        Integer expireDays = bo.getExpireDays();
        if (expireDays == null || expireDays <= 0 || expireDays > 3650) {
            // D-213「赠卡必带有效期」是这条校验的依据，但编号只留在代码里：
            // 运营看到的应该是「填什么才对」，不是我们的决策编号。
            throw new JbkException("赠卡有效期必须为 1~3650 天");
        }
        OptionalUtils.nullToElseThrow(userMapper.selectById(bo.getUserId()), "收卡用户不存在");

        String now = DateUtils.time();
        String expireTime = plusDays(now, expireDays);
        String cardNo = "GC" + DigestUtil.sha256Hex("GIFT:" + requestId).substring(0, 18).toUpperCase();
        // 缺省=全场通用：SCOPE_JSON 空在取水侧是「未配置=默认拒绝」，留空会发出一张
        // 永远取不了水的赠卡（与 D-213「消费不受限」相悖）；显式给定则发放时即校验，
        // 不合法的范围在发放口 fail-closed，而不是留给用户取水时才炸
        String scopeJson = StrUtil.blankToDefault(bo.getScopeJson(),
                "{\"scopeType\":\"" + WaterCardScope.TYPE_ALL + "\"}");
        WaterCardScope.normalize(scopeJson, "赠卡");

        WsCard card = new WsCard()
                .setCardNo(cardNo)
                .setCardType(1)
                .setUserId(bo.getUserId())
                .setBalanceAmount(grantFen)
                .setBalanceMl(grantMl)
                .setCardStatus(1)
                .setExpireTime(expireTime)
                .setScopeJson(scopeJson);
        card.setCreateTime(now);
        card.setUpdateTime(now);
        try {
            if (cardMapper.insert(card) != 1) {
                throw new JbkException("赠卡建卡失败");
            }
        }
        catch (DuplicateKeyException e) {
            // uk_card_no 撞键=同一请求号重放：读回既有卡，不发第二张。重放必须语义核验
            // （归属+权益金额+可用范围+有效期全量一致），否则改参复用旧请求号会被静默当成功。
            // 卡走锁定读（当前读+行锁，防核验期间被改）；流水与批次靠类级 READ_COMMITTED
            // 读最新已提交（R1 P1-1）——只修卡不修隔离级别时，并发败方看得见卡看不见流水，
            // 照样误判冲突。
            WsCard existing = cardMapper.selectByCardNoForUpdate(cardNo);
            if (ObjectUtil.isNull(existing) || ObjectUtil.notEqual(existing.getUserId(), bo.getUserId())) {
                throw new JbkException("赠卡请求号与既有发放冲突，请先核查该请求号的既有发放记录，勿更换请求号重试");
            }
            WsWalletFlow issued = walletFlowMapper.selectOne(Wrappers.lambdaQuery(WsWalletFlow.class)
                    .eq(WsWalletFlow::getBizIdempotencyKey, "GIFT:" + requestId));
            if (ObjectUtil.isNull(issued) || issued.getAmountChange() != grantFen || issued.getMlChange() != grantMl) {
                throw new JbkException("赠卡请求号已发放且权益参数与本次不符，请先核查既有发放记录");
            }
            if (ObjectUtil.notEqual(existing.getScopeJson(), scopeJson)) {
                throw new JbkException("赠卡请求号已发放且可用范围与本次不符，请先核查既有发放记录");
            }
            // R1 P1-2：有效期证据必须存在且一致——证据缺失即 fail-closed，绝不按「幂等成功」放行
            int issuedDays = requireIssuedExpireDays(existing.getId());
            if (ObjectUtil.notEqual(issuedDays, expireDays)) {
                throw new JbkException("赠卡请求号已发放且有效期与本次不符，请先核查既有发放记录");
            }
            return existing.getId();
        }

        // 发放流水（后台调整——字典既有值的首个写入方；全新资金写入路径必须
        // 有独立幂等键 + 关键审计，缺一不可）
        WsWalletFlow flow = new WsWalletFlow()
                .setCardId(card.getId())
                .setUserId(bo.getUserId())
                .setFlowType(TradeEnum.FlowType.ADJUST.getValue())
                .setAmountChange(grantFen)
                .setMlChange(grantMl)
                .setAmountAfter(grantFen)
                .setMlAfter(grantMl)
                .setBizIdempotencyKey("GIFT:" + requestId)
                .setFlowRemark(StrUtil.brief("运营赠卡发放：" + StrUtil.blankToDefault(bo.getRemark(), "—"), 200));
        flow.setCreateTime(now);
        flow.setUpdateTime(now);
        if (walletFlowMapper.insert(flow) != 1) {
            throw new JbkException("赠卡流水写入失败");
        }

        EntitlementBatchWriter.createOnGift(batchMapper, card.getId(), bo.getUserId(),
                grantFen, grantMl, expireTime,
                card.getScopeJson(),
                JSONUtil.toJsonStr(java.util.Map.of("giftRequestId", requestId, "expireDays", expireDays)),
                now);

        // 关键审计：人工资金动作必须留可靠痕（与发放同事务同灭）
        domainEventService.recordReliableOnceAs(OpsEnum.ActorPortal.MANAGE, operatorId,
                OpsEnum.EventType.ORDER_STATUS, cardNo, "GIFT_ISSUE:" + requestId,
                null, "运营赠卡：用户 " + bo.getUserId() + " 余额 " + grantFen + "分 水量 " + grantMl + "mL 有效期 " + expireDays + "天");
        return card.getId();
    }

    /**
     * 重放核验用：读回发放批次快照里的 expireDays（R1 P1-2 fail-closed 口径）。
     *
     * <p>命中既有卡的重放，其发放批次必须<b>存在且唯一</b>、快照必须可解析、expireDays 必须
     * 存在——任一证据缺失/重复/损坏都抛出让运营核查既有发放，绝不静默按「幂等成功」返回：
     * 证据链断裂时无人能证明既有发放的效期与本次一致，放行等于把差异藏进成功响应。
     * 刻意不回退为「按当前 EXPIRE_TIME 反推天数」——发放时刻不同，同天数会得出不同到期日，
     * 反推恒不可靠。</p>
     */
    private int requireIssuedExpireDays(Long cardId) {
        java.util.List<com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch> batches =
                batchMapper.selectList(Wrappers.lambdaQuery(
                                com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch.class)
                        .eq(com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch::getCardId, cardId));
        if (batches.isEmpty()) {
            throw new JbkException("赠卡请求号已发放但缺少发放批次证据，请先核查既有发放记录");
        }
        if (batches.size() > 1) {
            throw new JbkException("赠卡请求号已发放但存在多个发放批次，账本异常，请先核查既有发放记录");
        }
        String snap = batches.get(0).getPackageSnap();
        if (StrUtil.isBlank(snap)) {
            throw new JbkException("赠卡请求号已发放但发放快照缺失，请先核查既有发放记录");
        }
        Integer days;
        try {
            days = JSONUtil.parseObj(snap).getInt("expireDays");
        } catch (Exception unparsable) {
            throw new JbkException("赠卡请求号已发放但发放快照不可解析，请先核查既有发放记录");
        }
        if (days == null) {
            throw new JbkException("赠卡请求号已发放但发放快照缺少有效期证据，请先核查既有发放记录");
        }
        return days;
    }

    /** 有效期=发放时刻+天数（赠卡首发无既有有效期，不涉 D-205 续期公式）。 */
    private String plusDays(String now, int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(DateUtils.parse(now, "yyyyMMddHHmmss"));
        calendar.add(Calendar.DAY_OF_MONTH, days);
        return DateUtils.timeTransition(calendar.getTime());
    }
}
