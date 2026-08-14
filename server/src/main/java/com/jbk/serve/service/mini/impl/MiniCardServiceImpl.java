package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.user.WsCardMemberMapper;
import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.serve.service.mini.IMiniCardService;
import com.jbk.serve.service.mini.auth.MiniUserIdentitySupport;
import com.jbk.serve.service.mini.card.CardEligibility;
import com.jbk.serve.service.mini.card.CardMemberRule;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.trade.MemberDayLimitMath;
import com.jbk.serve.service.user.IWsCardService;
import com.jbk.serve.service.user.IWsUserService;
import com.jbk.tool.data.mini.bo.MiniCardMemberRevokeBo;
import com.jbk.tool.data.mini.bo.MiniCardMemberSaveBo;
import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
import com.jbk.tool.data.mini.vo.MiniCardBundleVo;
import com.jbk.tool.data.mini.vo.MiniCardDetailVo;
import com.jbk.tool.data.mini.vo.MiniCardMemberVo;
import com.jbk.tool.data.mini.vo.MiniCardSummaryVo;
import com.jbk.tool.data.mini.vo.MiniUsableCardVo;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.data.user.po.WsCardMember;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;
import com.jbk.serve.service.mini.recharge.RechargeExpiry;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 小程序水卡服务实现。
 * <p>
 * 复用 {@link IWsCardService} 的 ws_card 查询能力（其 mapper 与 PO 的 {@code @TableLogic}
 * 自动附加 DATA_STATUS=0，无需显式过滤逻辑删除）。仅做只读摘要与成员授权管理，不涉及余额增减，
 * 资金增减仍走下单/结算的原子扣减+流水路径（铁律1）。
 * </p>
 * <p>CARD-MEMBER：成员授权写路径全部经跨 DATA_STATUS 的手写 SQL——
 * uk_card_member_user 不含 DATA_STATUS，逻辑删除记录仍占键，重新授权必须复用原记录。</p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Service
@RequiredArgsConstructor
public class MiniCardServiceImpl implements IMiniCardService {

    /** 授权状态(1333)：1 生效。 */
    private static final int MEMBER_STATUS_ACTIVE = 1;

    /** 严格校验 yyyyMMddHHmmss（uuuu+STRICT：拒绝 20260231 这类正则放行的伪日期）。 */
    private static final DateTimeFormatter BIZ_TIME =
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);

    private final IWsCardService wsCardService;
    private final WsCardMemberMapper wsCardMemberMapper;
    private final IWsUserService wsUserService;
    private final WsUserIdentityMapper wsUserIdentityMapper;
    private final WsOrderMapper wsOrderMapper;
    private final WsCardEntitlementBatchMapper entitlementBatchMapper;

    @Override
    public MiniCardSummaryVo getPrimaryCard(Long userId) {
        if (userId == null) {
            return null;
        }
        // 铁律6：范围由会话 userId 强制圈定；主卡排序=虚拟卡(cardType=1)优先，其次最早开卡(ID 升序)。
        List<WsCard> cards = wsCardService.list(Wrappers.lambdaQuery(WsCard.class)
                .eq(WsCard::getUserId, userId)
                .orderByAsc(WsCard::getCardType)
                .orderByAsc(WsCard::getId)
                .last("LIMIT 1"));
        if (cards.isEmpty()) {
            return null;
        }
        return toSummary(cards.get(0));
    }

    @Override
    public MiniCardDetailVo getCardDetail(Long cardId, Long userId) {
        if (cardId == null || userId == null) {
            throw new JbkException("参数不完整，无法查询水卡");
        }
        WsCard card = ownCard(cardId, userId);
        MiniCardDetailVo vo = new MiniCardDetailVo();
        vo.setCardId(card.getId());
        vo.setCardNo(card.getCardNo());
        vo.setCardType(card.getCardType());
        vo.setCardStatus(card.getCardStatus());
        vo.setBalanceFen(card.getBalanceAmount());
        vo.setBalanceMl(card.getBalanceMl());
        vo.setExpireTime(card.getExpireTime());
        vo.setPackageName(readPackageName(card.getPackageSnap()));
        vo.setScopeDescription(describeScope(card.getScopeJson()));
        vo.setMembers(listMembers(card.getId()));
        // D-415：赠卡且名下有正式水卡时可合并（能力位只是展示投影，合并接口锁内另行强制校验）
        boolean gift = CardEligibility.isGiftCard(card);
        boolean mergeable = gift
                && (ObjectUtil.equals(card.getCardStatus(), 1) || ObjectUtil.equals(card.getCardStatus(), 3))
                && hasOtherPaidCard(userId, card.getId());
        vo.setCanMergeToPaidCard(mergeable);
        vo.setExpiringBundles(listExpiringBundles(card.getId()));
        return vo;
    }

    /**
     * 名下（除指定卡外）是否还有未注销的付费卡（付费卡=有订单锚或永久）。
     * 口径与 {@code MiniRechargeServiceImpl#hasOtherPaidCard} 及 listUsableCards 的
     * 内存判定同构——三处必须同判据，否则「详情说可合并、合并接口说无主卡」。
     */
    private boolean hasOtherPaidCard(Long userId, Long exceptCardId) {
        // 名额口径（审计 P1-2）：状态 1/2/3/4 全占名额；「非赠卡」经 apply 引用 CardEligibility.SQL_NOT_GIFT 唯一谓词
        return wsCardService.count(Wrappers.lambdaQuery(WsCard.class)
                .eq(WsCard::getUserId, userId)
                .ne(WsCard::getId, exceptCardId)
                .apply(CardEligibility.SQL_NOT_GIFT)) > 0;
    }

    /**
     * 带到期时间且尚有剩余的可消费批次摘要（按到期升序）——「合并后显示多久到期」的数据源。
     * 状态集 (1, 6) 与消费选取一致：退款锁定/已退款/已过期批次不再对用户展示为可用权益。
     */
    private List<MiniCardBundleVo> listExpiringBundles(Long cardId) {
        List<WsCardEntitlementBatch> batches = entitlementBatchMapper.selectList(
                Wrappers.lambdaQuery(WsCardEntitlementBatch.class)
                        .eq(WsCardEntitlementBatch::getCardId, cardId)
                        .in(WsCardEntitlementBatch::getBatchStatus, 1, 6)
                        .isNotNull(WsCardEntitlementBatch::getExpireTime)
                        .and(w -> w.gt(WsCardEntitlementBatch::getRemainAmountFen, 0)
                                .or().gt(WsCardEntitlementBatch::getRemainWaterMl, 0))
                        .orderByAsc(WsCardEntitlementBatch::getExpireTime)
                        .orderByAsc(WsCardEntitlementBatch::getId));
        return batches.stream().map(b -> {
            MiniCardBundleVo vo = new MiniCardBundleVo();
            vo.setRemainFen(nvl(b.getRemainAmountFen()));
            vo.setRemainMl(nvl(b.getRemainWaterMl()));
            vo.setExpireTime(b.getExpireTime());
            return vo;
        }).toList();
    }

    @Override
    public List<MiniUsableCardVo> listUsableCards(Long userId) {
        if (userId == null) {
            return List.of();
        }
        String now = DateUtils.time();
        List<MiniUsableCardVo> result = new ArrayList<>();
        // 本人持卡（OWNER）：排序与主卡口径一致（虚拟卡优先、最早开卡在前），保证列表稳定可复现
        List<WsCard> ownCards = wsCardService.list(Wrappers.lambdaQuery(WsCard.class)
                .eq(WsCard::getUserId, userId)
                .orderByAsc(WsCard::getCardType)
                .orderByAsc(WsCard::getId));
        for (WsCard card : ownCards) {
            // 除本卡外是否还有未注销的付费卡（付费卡=有订单锚或永久；赠卡=带期且无锚）
            // 名额口径（审计 P1-2）：CardEligibility 单一出处——注销(4)付费卡同样占名额
            boolean hasOtherPaid = ownCards.stream().anyMatch(other ->
                    !other.getId().equals(card.getId()) && CardEligibility.occupiesPaidSlot(other));
            result.add(toUsableCard(card, MiniUsableCardVo.ROLE_OWNER, null, hasOtherPaid));
        }
        // 成员授权卡（MEMBER）：仅当前有效授权（状态生效 + 时间窗内），卡本身走 @TableLogic 过滤删除
        List<WsCardMember> grants = wsCardMemberMapper.selectList(
                Wrappers.lambdaQuery(WsCardMember.class)
                        .eq(WsCardMember::getMemberUserId, userId)
                        .eq(WsCardMember::getMemberStatus, MEMBER_STATUS_ACTIVE)
                        .orderByAsc(WsCardMember::getCardId));
        for (WsCardMember grant : grants) {
            if (!CardMemberRule.isActive(grant, now)) {
                continue;
            }
            WsCard card = wsCardService.getById(grant.getCardId());
            // 卡缺失/已删除跳过；数据污染出现「自己被授权为自己卡的成员」时按 OWNER 已列出，不重复
            if (ObjectUtil.isNull(card) || ObjectUtil.equals(card.getUserId(), userId)) {
                continue;
            }
            result.add(toUsableCard(card, MiniUsableCardVo.ROLE_MEMBER, remainingOf(grant, now)));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MiniCardMemberVo saveMember(MiniCardMemberSaveBo bo, Long userId) {
        if (userId == null) {
            throw new JbkException("登录状态异常，请重新登录");
        }
        // 只能管理本人持有的卡：归属写进 WHERE，他人卡与不存在卡同口径拒绝
        WsCard card = ownCard(bo.getCardId(), userId);
        String memberName = StrUtil.trim(bo.getMemberName());
        if (StrUtil.isBlank(memberName) || memberName.length() > 50) {
            throw new JbkException("成员备注名不能为空且不超过 50 字");
        }
        Long dayLimitMl = bo.getDayLimitMl();
        if (ObjectUtil.isNotNull(dayLimitMl) && dayLimitMl <= 0) {
            throw new JbkException("单日限额必须为正整数（留空表示不限）");
        }
        validateAuthorizeWindow(bo.getEffectiveTime(), bo.getExpireTime());

        // 手机号只匹配已存在且可用的唯一用户：跨 DATA_STATUS 查询（删除态账号仍占号，不得复用），
        // 唯一性收敛与可用性断言复用 L2-AUTH 同一套判定；不自动创建用户。
        WsUser target = MiniUserIdentitySupport.resolveUnique(
                wsUserIdentityMapper.selectByPhoneIncludingDeleted(bo.getPhone()),
                "该手机号身份数据异常，请联系客服处理");
        if (ObjectUtil.isNull(target)) {
            // 查不到号=从未登录或已登录未绑手机号，文案必须同时给出两条出路（只说「先登录」会让已登录者反复重登）
            throw new JbkException("该手机号未绑定任何账号。请对方先登录小程序，并在「我的」中完成手机号绑定后再试");
        }
        MiniUserIdentitySupport.assertUsable(target);
        if (ObjectUtil.equals(target.getId(), userId)) {
            throw new JbkException("不能将本人添加为成员");
        }

        String now = DateUtils.time();
        Long rowId;
        if (ObjectUtil.isNotNull(bo.getMemberId())) {
            // 编辑：跨 DATA_STATUS 定位记录并校验归属；编辑不能换人（换人=撤销+重加）
            WsCardMember existed = wsCardMemberMapper.selectByIdIncludingDeleted(bo.getMemberId());
            if (ObjectUtil.isNull(existed) || ObjectUtil.notEqual(existed.getCardId(), card.getId())) {
                throw new JbkException("授权成员不存在");
            }
            if (ObjectUtil.notEqual(existed.getMemberUserId(), target.getId())) {
                throw new JbkException("编辑不能更换成员，请先撤销原授权后重新添加");
            }
            rowId = existed.getId();
            requireOneRow(wsCardMemberMapper.reauthorize(rowId, card.getId(), memberName,
                    dayLimitMl, bo.getEffectiveTime(), bo.getExpireTime(), userId, now));
        } else {
            // 新增：uk_card_member_user 决定复用还是插入——同人历史记录（含已解除/逻辑删除）一律复用原行
            WsCardMember existed = wsCardMemberMapper.selectByCardAndUserIncludingDeleted(
                    card.getId(), target.getId());
            if (ObjectUtil.isNotNull(existed)) {
                rowId = existed.getId();
                requireOneRow(wsCardMemberMapper.reauthorize(rowId, card.getId(), memberName,
                        dayLimitMl, bo.getEffectiveTime(), bo.getExpireTime(), userId, now));
            } else {
                WsCardMember row = new WsCardMember()
                        .setCardId(card.getId())
                        .setMemberUserId(target.getId())
                        .setMemberName(memberName)
                        .setDayLimitMl(dayLimitMl)
                        .setEffectiveTime(bo.getEffectiveTime())
                        .setExpireTime(bo.getExpireTime());
                // 审计字段显式写入（insertMember 绕开 MP 自动填充；BaseEntity setter 非链式）
                row.setCreateBy(userId);
                row.setCreateTime(now);
                row.setUpdateBy(userId);
                row.setUpdateTime(now);
                try {
                    wsCardMemberMapper.insertMember(row);
                    rowId = row.getId();
                } catch (DuplicateKeyException concurrent) {
                    // 并发重加撞唯一键：输方重读唯一键定位的原记录并复用（与串行路径同一收敛点）
                    WsCardMember winner = wsCardMemberMapper.selectByCardAndUserIncludingDeleted(
                            card.getId(), target.getId());
                    if (ObjectUtil.isNull(winner)) {
                        throw new JbkException("成员授权保存冲突，请重试");
                    }
                    rowId = winner.getId();
                    requireOneRow(wsCardMemberMapper.reauthorize(rowId, card.getId(), memberName,
                            dayLimitMl, bo.getEffectiveTime(), bo.getExpireTime(), userId, now));
                }
            }
        }
        WsCardMember saved = wsCardMemberMapper.selectByIdIncludingDeleted(rowId);
        if (ObjectUtil.isNull(saved)) {
            throw new JbkException("成员授权保存失败，请重试");
        }
        return toMemberVo(saved, maskPhone(target.getUserPhone()));
    }

    @Override
    public MiniCardMemberVo revokeMember(MiniCardMemberRevokeBo bo, Long userId) {
        if (userId == null) {
            throw new JbkException("登录状态异常，请重新登录");
        }
        WsCard card = ownCard(bo.getCardId(), userId);
        WsCardMember existed = wsCardMemberMapper.selectByIdIncludingDeleted(bo.getMemberId());
        if (ObjectUtil.isNull(existed) || ObjectUtil.notEqual(existed.getCardId(), card.getId())
                || ObjectUtil.notEqual(existed.getDataStatus(), 0)) {
            throw new JbkException("授权成员不存在");
        }
        // 条件状态更新（仅 1生效→2已解除）+ 影响行数校验：并发撤销/已解除时影响 0 行，明确拒绝
        int affected = wsCardMemberMapper.revokeActive(existed.getId(), card.getId(), userId, DateUtils.time());
        if (affected != 1) {
            throw new JbkException("该成员授权已解除或状态已变化，请刷新后重试");
        }
        WsCardMember revoked = wsCardMemberMapper.selectByIdIncludingDeleted(existed.getId());
        return toMemberVo(revoked, memberMaskedPhone(revoked.getMemberUserId()));
    }

    /** 本人持卡定位：归属条件写进 WHERE，他人的卡直接查不出来（不泄露存在性）。 */
    private WsCard ownCard(Long cardId, Long userId) {
        WsCard card = wsCardService.getOne(Wrappers.lambdaQuery(WsCard.class)
                .eq(WsCard::getId, cardId)
                .eq(WsCard::getUserId, userId)
                .last("LIMIT 1"));
        if (ObjectUtil.isNull(card)) {
            throw new JbkException("水卡不存在或无权访问");
        }
        return card;
    }

    /** 授权时间窗校验：格式经 Bo 正则初筛后，这里再做严格日历校验与先后关系校验。 */
    private void validateAuthorizeWindow(String effectiveTime, String expireTime) {
        requireValidBizTime(effectiveTime, "生效时间");
        requireValidBizTime(expireTime, "失效时间");
        if (StrUtil.isNotBlank(effectiveTime) && StrUtil.isNotBlank(expireTime)
                && effectiveTime.compareTo(expireTime) > 0) {
            throw new JbkException("生效时间不得晚于失效时间");
        }
    }

    private void requireValidBizTime(String value, String label) {
        if (StrUtil.isBlank(value)) {
            return;
        }
        try {
            LocalDateTime.parse(value, BIZ_TIME);
        } catch (Exception invalid) {
            throw new JbkException(label + "不是合法时间（yyyyMMddHHmmss）");
        }
    }

    private void requireOneRow(int affected) {
        if (affected != 1) {
            throw new JbkException("成员授权保存失败，请重试");
        }
    }

    /** 摘要 + 能力位：角色 × 卡形态共同决定（OWNER 且永久卡可充值可管成员；MEMBER 仅取水）。 */
    private MiniUsableCardVo toUsableCard(WsCard card, String role, Long remainingDailyLimitMl) {
        return toUsableCard(card, role, remainingDailyLimitMl, true);
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }

    /**
     * @param hasOtherPaidCard 同名下（除本卡外）是否还有未注销的付费卡——赠卡转正资格
     *                         （D-416）需要它；成员视角恒传 true（成员不谈充值）
     */
    private MiniUsableCardVo toUsableCard(WsCard card, String role, Long remainingDailyLimitMl,
                                          boolean hasOtherPaidCard) {
        MiniUsableCardVo vo = new MiniUsableCardVo();
        vo.setCardId(card.getId());
        vo.setCardNo(card.getCardNo());
        vo.setCardType(card.getCardType());
        vo.setCardStatus(card.getCardStatus());
        vo.setBalanceFen(card.getBalanceAmount());
        vo.setBalanceMl(card.getBalanceMl());
        vo.setExpireTime(card.getExpireTime());
        boolean owner = MiniUsableCardVo.ROLE_OWNER.equals(role);
        vo.setAccessRole(role);
        // 充值入口（D-416）：永久付费卡恒可充；赠卡仅当权益用完或已到期且名下无其他付费卡时开放
        // （该笔充值即转正）；有效期内且有权益的赠卡无入口（付费余额会被到期日绑架）
        boolean gift = CardEligibility.isGiftCard(card);
        boolean giftPromotable = gift && !hasOtherPaidCard
                && ((nvl(card.getBalanceAmount()) == 0L && nvl(card.getBalanceMl()) == 0L)
                        || RechargeExpiry.naturallyExpired(card.getExpireTime(), DateUtils.time()));
        vo.setCanRecharge(owner && (StrUtil.isBlank(card.getExpireTime()) || giftPromotable));
        vo.setCanManageMembers(owner);
        vo.setRemainingDailyLimitMl(owner ? null : remainingDailyLimitMl);
        // D-415：OWNER 的赠卡且名下有正式水卡时可合并（含已自然过期赠卡——合并即作废清理）
        vo.setCanMergeToPaidCard(owner && gift && hasOtherPaidCard
                && (ObjectUtil.equals(card.getCardStatus(), 1) || ObjectUtil.equals(card.getCardStatus(), 3)));
        return vo;
    }

    /**
     * 成员当日剩余限额（只读展示估算）：限额-当天占用，下限 0。无限额返回 null。
     * 事务内的最终判定在取水事务锁内另行统计，本值不构成放行依据。
     */
    private Long remainingOf(WsCardMember grant, String now) {
        if (ObjectUtil.isNull(grant.getDayLimitMl())) {
            return null;
        }
        try {
            long occupied = MemberDayLimitMath.occupiedMl(wsOrderMapper.selectMemberDayWaterOrders(
                    grant.getCardId(), grant.getMemberUserId(),
                    MemberDayLimitMath.dayStart(now), MemberDayLimitMath.dayEnd(now)));
            return MemberDayLimitMath.remaining(grant.getDayLimitMl(), occupied);
        } catch (ArithmeticException overflow) {
            // 占用累加溢出＝数据异常：展示按 0 剩余（fail-closed），事务侧同样会拒绝
            return 0L;
        }
    }

    /** 从 ws_card.PACKAGE_SNAP 快照读套餐名；快照缺失或非法一律返回 null（不回查 ws_package，快照即事实）。 */
    private String readPackageName(String packageSnap) {
        if (StrUtil.isBlank(packageSnap)) {
            return null;
        }
        try {
            JSONObject snap = JSON.parseObject(packageSnap);
            return snap == null ? null : StrUtil.blankToDefault(snap.getString("packageName"), null);
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * SCOPE_JSON → 用户可读描述。解析统一走 {@link WaterCardScope}；空值/非法一律返回
     * “未配置（默认拒绝）”，绝不把不可解析范围解释为“全场通用”。
     */
    private String describeScope(String scopeJson) {
        try {
            return WaterCardScope.normalize(scopeJson, "水卡").description();
        } catch (Exception ignore) {
            // 解析失败=该卡当前无法通过任何范围校验（取水/充值同口径拒绝），对外统一按未配置描述。
            return "未配置（默认拒绝）";
        }
    }

    /** 授权成员：含已解除记录，由 enabled 标识；手机号一律脱敏。 */
    private List<MiniCardMemberVo> listMembers(Long cardId) {
        List<WsCardMember> members = wsCardMemberMapper.selectList(
                Wrappers.lambdaQuery(WsCardMember.class)
                        .eq(WsCardMember::getCardId, cardId)
                        .orderByAsc(WsCardMember::getId));
        if (members.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = members.stream().map(WsCardMember::getMemberUserId).filter(ObjectUtil::isNotNull).toList();
        Map<Long, String> phoneById = userIds.isEmpty() ? Map.of() : wsUserService
                .list(Wrappers.lambdaQuery(WsUser.class).in(WsUser::getId, userIds))
                .stream()
                .collect(Collectors.toMap(WsUser::getId, u -> maskPhone(u.getUserPhone()), (a, b) -> a));
        return members.stream()
                .map(m -> toMemberVo(m, phoneById.getOrDefault(m.getMemberUserId(), "")))
                .toList();
    }

    private MiniCardMemberVo toMemberVo(WsCardMember m, String maskedPhone) {
        MiniCardMemberVo vo = new MiniCardMemberVo();
        vo.setMemberId(m.getId());
        vo.setMemberUserId(m.getMemberUserId());
        vo.setMemberName(m.getMemberName());
        vo.setMaskedPhone(maskedPhone);
        vo.setDayLimitMl(m.getDayLimitMl());
        vo.setEffectiveTime(m.getEffectiveTime());
        vo.setExpireTime(m.getExpireTime());
        vo.setEnabled(ObjectUtil.equals(m.getMemberStatus(), MEMBER_STATUS_ACTIVE));
        return vo;
    }

    /** 按成员用户ID取脱敏手机号；用户缺失/已删除时返回空串，绝不回落明文。 */
    private String memberMaskedPhone(Long memberUserId) {
        if (ObjectUtil.isNull(memberUserId)) {
            return "";
        }
        WsUser user = wsUserService.getById(memberUserId);
        return ObjectUtil.isNull(user) ? "" : maskPhone(user.getUserPhone());
    }

    /** 11 位手机号脱敏为 138****5678；长度异常时整体屏蔽，绝不回落明文。 */
    private String maskPhone(String phone) {
        // 唯一实现在 PhoneMask：脱敏规则不许分叉（曾两处各写一份）
        return com.jbk.tool.utils.PhoneMask.mask(phone);
    }

    /** ws_card → 小程序摘要：BALANCE_AMOUNT 映射为契约字段 balanceFen。 */
    private MiniCardSummaryVo toSummary(WsCard card) {
        MiniCardSummaryVo vo = new MiniCardSummaryVo();
        vo.setCardId(card.getId());
        vo.setCardNo(card.getCardNo());
        vo.setCardType(card.getCardType());
        vo.setCardStatus(card.getCardStatus());
        vo.setBalanceFen(card.getBalanceAmount());
        vo.setBalanceMl(card.getBalanceMl());
        vo.setExpireTime(card.getExpireTime());
        return vo;
    }
}
