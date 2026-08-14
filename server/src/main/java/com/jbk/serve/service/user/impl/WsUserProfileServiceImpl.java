package com.jbk.serve.service.user.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.ops.WsDomainEventMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCardMapper;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.serve.service.user.IWsUserProfileService;
import com.jbk.serve.service.user.IWsUserService;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.ops.po.WsDomainEvent;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.user.bo.WsUserProfileBo;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.data.user.vo.WsUserAuditVo;
import com.jbk.tool.data.user.vo.WsUserFlowVo;
import com.jbk.tool.data.user.vo.WsUserOrderVo;
import com.jbk.tool.data.user.vo.WsUserRelationItemVo;
import com.jbk.tool.data.user.vo.WsUserRelationVo;
import com.jbk.tool.data.user.vo.WsUserVo;
import com.jbk.tool.utils.OptionalUtils;
import com.jbk.tool.utils.PhoneMask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户档案只读聚合实现。
 *
 * <p>三条共同约束：</p>
 * <ul>
 *   <li>每个分区都先按 userId 校验用户存在，再取该分区数据——不接受「拿一个不存在的 ID
 *       换一页别人的数据」这类越界读取。</li>
 *   <li>一切列表分页，绝不返回全量；页参一律经 {@link WsUserProfileBo#pageOrDefault()} /
 *       {@link WsUserProfileBo#sizeOrDefault()} 夹紧后才建 Page——直传调用方的 size 时
 *       {@code size=-1} 会让 MyBatis-Plus 不生成 LIMIT，「已分页」根本不成立。
 *       关系只呈现直接一级，不递归。</li>
 *   <li>只读：本类不含任何 update/insert 调用，资产与状态在这里只被观察。</li>
 * </ul>
 *
 * @author dakang
 * @since 2026-08-13
 */
@Service
@RequiredArgsConstructor
public class WsUserProfileServiceImpl implements IWsUserProfileService {

    /**
     * 审计分区允许归属到 C 端用户的操作端口：用户端/机主端/配送端。
     * portal=1（公司后台）的行 ACTOR_ID 存的是 api_employee.ID，与 ws_user.ID 是两套互不相干的自增空间，
     * 必须排除；渠道/系统/设备端不由某个自然人发起，同样不进个人档案。
     */
    private static final List<Integer> C_SIDE_ACTOR_PORTALS = List.of(
            OpsEnum.ActorPortal.USER.getValue(),
            OpsEnum.ActorPortal.OWNER.getValue(),
            OpsEnum.ActorPortal.COURIER.getValue());

    private final IWsUserService wsUserService;
    private final WsUserMapper userMapper;
    private final WsCardMapper cardMapper;
    private final WsOrderMapper orderMapper;
    private final WsWalletFlowMapper walletFlowMapper;
    private final WsDomainEventMapper domainEventMapper;

    @Override
    public WsUserVo getIdentity(Long userId) {
        // 复用用户服务的出口加工（脱敏 + 能力标签），避免第二份脱敏实现随时间漂移
        return wsUserService.getData(userId);
    }

    @Override
    public PageDataVo<WsUserOrderVo> pageOrders(WsUserProfileBo bo) {
        requireUser(bo.getUserId());
        Page<WsOrder> page = orderMapper.selectPage(new Page<>(bo.pageOrDefault(), bo.sizeOrDefault()),
                Wrappers.lambdaQuery(WsOrder.class)
                        .eq(WsOrder::getUserId, bo.getUserId())
                        .orderByDesc(WsOrder::getId));
        List<WsUserOrderVo> list = page.getRecords().stream()
                .map(order -> BeanUtil.copyProperties(order, WsUserOrderVo.class))
                .collect(Collectors.toList());
        return PageDataVo.getPageData(list, page.getTotal());
    }

    @Override
    public PageDataVo<WsUserFlowVo> pageFlows(WsUserProfileBo bo) {
        requireUser(bo.getUserId());
        // 资金事实挂在卡上，先取本人名下卡（idx_card_user 命中），再按 CARD_ID 分页流水
        // （命中 idx_flow_card_time）。没有卡就是没有流水，直接返回空页，不去扫全表。
        List<WsCard> cards = cardMapper.selectList(Wrappers.lambdaQuery(WsCard.class)
                .select(WsCard::getId, WsCard::getCardNo)
                .eq(WsCard::getUserId, bo.getUserId()));
        if (CollUtil.isEmpty(cards)) {
            return PageDataVo.getPageData(new ArrayList<>(), 0L);
        }
        Map<Long, String> cardNoMap = cards.stream()
                .collect(Collectors.toMap(WsCard::getId, WsCard::getCardNo, (a, b) -> a));
        Page<WsWalletFlow> page = walletFlowMapper.selectPage(new Page<>(bo.pageOrDefault(), bo.sizeOrDefault()),
                Wrappers.lambdaQuery(WsWalletFlow.class)
                        .in(WsWalletFlow::getCardId, cardNoMap.keySet())
                        .orderByDesc(WsWalletFlow::getId));
        List<WsUserFlowVo> list = page.getRecords().stream()
                .map(flow -> BeanUtil.copyProperties(flow, WsUserFlowVo.class)
                        .setCardNo(cardNoMap.get(flow.getCardId())))
                .collect(Collectors.toList());
        return PageDataVo.getPageData(list, page.getTotal());
    }

    @Override
    public WsUserRelationVo getRelation(Long userId) {
        WsUser user = requireUser(userId);
        WsUserRelationVo vo = new WsUserRelationVo()
                .setOwnInviteCode(user.getOwnInviteCode())
                .setPromoCode(user.getPromoCode())
                .setReferrerUserId(user.getReferrerUserId())
                .setReferrerMissing(Boolean.FALSE);
        if (ObjectUtil.isNotNull(user.getReferrerUserId())) {
            WsUser referrer = userMapper.selectById(user.getReferrerUserId());
            if (ObjectUtil.isNull(referrer)) {
                // 绑定列还指着一个已不可用的账号：如实标记，不静默抹平成「未绑定」——
                // 抹平会让归因异常在页面上彻底消失，运营再也发现不了。
                vo.setReferrerMissing(Boolean.TRUE);
            }
            else {
                vo.setReferrerUserName(referrer.getUserName())
                        .setReferrerUserPhone(PhoneMask.mask(referrer.getUserPhone()));
            }
        }
        vo.setDirectInviteeCount(userMapper.selectCount(Wrappers.lambdaQuery(WsUser.class)
                .eq(WsUser::getReferrerUserId, userId)));
        return vo;
    }

    @Override
    public PageDataVo<WsUserRelationItemVo> pageInvitees(WsUserProfileBo bo) {
        requireUser(bo.getUserId());
        Page<WsUser> page = userMapper.selectPage(new Page<>(bo.pageOrDefault(), bo.sizeOrDefault()),
                Wrappers.lambdaQuery(WsUser.class)
                        .eq(WsUser::getReferrerUserId, bo.getUserId())
                        .orderByDesc(WsUser::getId));
        List<WsUserRelationItemVo> list = page.getRecords().stream()
                .map(item -> new WsUserRelationItemVo()
                        .setId(item.getId())
                        .setUserName(item.getUserName())
                        .setUserPhone(PhoneMask.mask(item.getUserPhone()))
                        .setCreateTime(item.getCreateTime()))
                .collect(Collectors.toList());
        return PageDataVo.getPageData(list, page.getTotal());
    }

    @Override
    public PageDataVo<WsUserAuditVo> pageAudits(WsUserProfileBo bo) {
        requireUser(bo.getUserId());
        // ACTOR_ID 目前没有独立索引，因此这里必须保持分页 + 倒序，绝不放开「导出全部」之类的全量口子。
        // 索引补齐属数据库授权项，已随本包登记；补上之前查询规模由 sizeOrDefault 的页大小上限兜住。
        //
        // ACTOR_PORTAL 必须一起过滤：ws_domain_event.ACTOR_ID 同时承载 api_employee.ID（后台，portal=1）
        // 与 ws_user.ID（用户/机主/配送端，portal=2/3/4），两表都是自增主键、ID 空间完全重叠。
        // 只比 ACTOR_ID 会把「员工 ID 恰好等于该用户 ID」的后台操作当成这位 C 端用户的行为列出来
        // （基线里 api_employee.ID=1 是超级管理员、ws_user.ID=1 是张女士，一开档案就串号），
        // 既误归属，又把该账号本无权查看的业务对象键（设备编号、工单号）经用户档案页带出去。
        Page<WsDomainEvent> page = domainEventMapper.selectPage(new Page<>(bo.pageOrDefault(), bo.sizeOrDefault()),
                Wrappers.lambdaQuery(WsDomainEvent.class)
                        .eq(WsDomainEvent::getActorId, bo.getUserId())
                        .in(WsDomainEvent::getActorPortal, C_SIDE_ACTOR_PORTALS)
                        .orderByDesc(WsDomainEvent::getId));
        List<WsUserAuditVo> list = page.getRecords().stream()
                .map(this::toAuditVo)
                .collect(Collectors.toList());
        return PageDataVo.getPageData(list, page.getTotal());
    }

    /** 事件行 → 审计展示行。名称就地翻译成业务语言；事件报文 EVENT_PAYLOAD 一律不带出。 */
    private WsUserAuditVo toAuditVo(WsDomainEvent event) {
        return new WsUserAuditVo()
                .setId(event.getId())
                .setEventType(event.getEventType())
                .setEventTypeName(describe(event.getEventType(), value -> OpsEnum.EventType.getType(value).getDesc()))
                .setEventKey(event.getEventKey())
                .setActorPortal(event.getActorPortal())
                .setActorPortalName(describe(event.getActorPortal(), value -> OpsEnum.ActorPortal.getType(value).getDesc()))
                .setCreateTime(event.getCreateTime());
    }

    /**
     * 枚举名称翻译。库里出现枚举未登记的历史值时返回空而不是抛错：
     * 一条老记录的类型没登记，不该让整页审计打不开。
     */
    private String describe(Integer value, Function<Integer, String> resolver) {
        if (ObjectUtil.isNull(value)) {
            return null;
        }
        try {
            return resolver.apply(value);
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** 分区共用前置：用户必须存在且未被逻辑删除，否则直接拒绝——不返回空列表冒充「这个人没有数据」。 */
    private WsUser requireUser(Long userId) {
        OptionalUtils.nullToElseThrow(userId, "用户信息不为空");
        WsUser user = userMapper.selectById(userId);
        OptionalUtils.nullToElseThrow(user, "用户信息不存在");
        return user;
    }
}
