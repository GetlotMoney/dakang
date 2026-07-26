package com.jbk.serve.service.user.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.user.WsCardMapper;
import com.jbk.serve.mapper.user.WsCardMemberMapper;
import com.jbk.serve.service.user.IWsUserService;
import com.jbk.serve.service.user.IWsCardService;
import com.jbk.tool.consts.user.UserEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.data.user.bo.WsCardBo;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.data.user.po.WsCardMember;
import com.jbk.tool.data.user.vo.WsCardMemberVo;
import com.jbk.tool.data.user.vo.WsCardVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.OptionalUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 水卡服务实现
 * <p>
 * 资金铁律：本服务不做余额增减（开卡/充值/迁移属商业一期，需财务审核）；
 * 状态机仅开放 1正常↔2冻结 的后台手动流转，3已过期/4已注销为系统终态。
 * </p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Service
public class WsCardServiceImpl extends ServiceImpl<WsCardMapper, WsCard> implements IWsCardService {

    @Autowired
    private WsCardMemberMapper cardMemberMapper;
    @Autowired
    private IWsUserService wsUserService;

    @Override
    public PageDataVo<WsCardVo> pageData(WsCardBo cardBo) {
        Page<WsCard> page = page(new Page<>(cardBo.getCurrent(), cardBo.getSize()),
                Wrappers.lambdaQuery(WsCard.class)
                        .like(StrUtil.isNotBlank(cardBo.getCardNo()), WsCard::getCardNo, cardBo.getCardNo())
                        .eq(ObjectUtil.isNotNull(cardBo.getCardType()), WsCard::getCardType, cardBo.getCardType())
                        .eq(ObjectUtil.isNotNull(cardBo.getCardStatus()), WsCard::getCardStatus, cardBo.getCardStatus())
                        .eq(ObjectUtil.isNotNull(cardBo.getUserId()), WsCard::getUserId, cardBo.getUserId())
                        .orderByDesc(WsCard::getId));
        List<WsCardVo> voList = page.getRecords().stream()
                .map(e -> BeanUtil.copyProperties(e, WsCardVo.class))
                .collect(Collectors.toList());
        fillDerived(voList);
        return PageDataVo.getPageData(voList, page.getTotal());
    }

    @Override
    public WsCardVo getData(Long id) {
        WsCard card = getById(id);
        OptionalUtils.nullToElseThrow(card, "水卡不存在");
        WsCardVo vo = BeanUtil.copyProperties(card, WsCardVo.class);
        fillDerived(CollUtil.newArrayList(vo));
        vo.setMemberList(listMembers(id));
        return vo;
    }

    @Override
    public List<WsCardVo> listByUser(Long userId) {
        OptionalUtils.nullToElseThrow(userId, "用户信息不为空");
        List<WsCard> cards = list(Wrappers.lambdaQuery(WsCard.class)
                .eq(WsCard::getUserId, userId)
                .orderByDesc(WsCard::getId));
        List<WsCardVo> voList = cards.stream()
                .map(e -> BeanUtil.copyProperties(e, WsCardVo.class))
                .collect(Collectors.toList());
        fillDerived(voList);
        return voList;
    }

    @Override
    public Boolean changeStatus(WsCardBo cardBo) {
        WsCard card = getById(cardBo.getId());
        OptionalUtils.nullToElseThrow(card, "水卡不存在");
        Integer target = cardBo.getTargetStatus();
        OptionalUtils.nullToElseThrow(target, "目标状态不为空");
        int normal = UserEnum.CardStatus.NORMAL.getValue();
        int frozen = UserEnum.CardStatus.FROZEN.getValue();
        if (target != normal && target != frozen) {
            throw new JbkException("后台仅允许在正常与冻结之间流转");
        }
        int current = card.getCardStatus();
        if (current != normal && current != frozen) {
            throw new JbkException("当前卡状态为「" + UserEnum.CardStatus.getType(current).getDesc() + "」，不可手动变更");
        }
        if (current == target) {
            throw new JbkException("卡已处于目标状态，无需变更");
        }
        // @LogOperation 记录状态变更参数与原因，满足高风险操作审计要求。
        // 更新条件包含原状态以实现乐观并发控制；影响行数为 0 表示状态已发生变化。
        boolean updated = update(Wrappers.lambdaUpdate(WsCard.class)
                .eq(WsCard::getId, card.getId())
                .eq(WsCard::getCardStatus, current)
                .set(WsCard::getCardStatus, target));
        if (!updated) {
            throw new JbkException("卡状态已被其他操作变更，请刷新后重试");
        }
        return Boolean.TRUE;
    }

    /** 卡详情的授权成员列表（含成员用户姓名/手机号派生） */
    private List<WsCardMemberVo> listMembers(Long cardId) {
        List<WsCardMember> members = cardMemberMapper.selectList(Wrappers.lambdaQuery(WsCardMember.class)
                .eq(WsCardMember::getCardId, cardId)
                .orderByDesc(WsCardMember::getId));
        List<WsCardMemberVo> voList = members.stream()
                .map(e -> BeanUtil.copyProperties(e, WsCardMemberVo.class))
                .collect(Collectors.toList());
        List<Long> userIdList = voList.stream()
                .map(WsCardMemberVo::getMemberUserId)
                .filter(ObjectUtil::isNotNull)
                .distinct()
                .collect(Collectors.toList());
        if (CollUtil.isNotEmpty(userIdList)) {
            Map<Long, WsUser> userMap = wsUserService.listByIds(userIdList).stream()
                    .collect(Collectors.toMap(WsUser::getId, Function.identity()));
            voList.forEach(vo -> {
                WsUser user = userMap.get(vo.getMemberUserId());
                if (ObjectUtil.isNotNull(user)) {
                    vo.setMemberUserName(user.getUserName());
                    vo.setMemberUserPhone(user.getUserPhone());
                }
            });
        }
        return voList;
    }

    /** 填充派生字段：持卡人姓名/手机号、生效成员数 */
    private void fillDerived(List<WsCardVo> voList) {
        if (CollUtil.isEmpty(voList)) {
            return;
        }
        List<Long> userIdList = voList.stream()
                .map(WsCardVo::getUserId)
                .filter(ObjectUtil::isNotNull)
                .distinct()
                .collect(Collectors.toList());
        if (CollUtil.isNotEmpty(userIdList)) {
            Map<Long, WsUser> userMap = wsUserService.listByIds(userIdList).stream()
                    .collect(Collectors.toMap(WsUser::getId, Function.identity()));
            voList.forEach(vo -> {
                WsUser user = userMap.get(vo.getUserId());
                if (ObjectUtil.isNotNull(user)) {
                    vo.setUserName(user.getUserName());
                    vo.setUserPhone(user.getUserPhone());
                }
            });
        }
        // 生效授权成员数（一次查出按卡分组，避免 N+1）
        List<Long> cardIdList = voList.stream().map(WsCardVo::getId).collect(Collectors.toList());
        List<WsCardMember> members = cardMemberMapper.selectList(Wrappers.lambdaQuery(WsCardMember.class)
                .in(WsCardMember::getCardId, cardIdList)
                .eq(WsCardMember::getMemberStatus, UserEnum.CardMemberStatus.ACTIVE.getValue()));
        Map<Long, Long> countMap = members.stream()
                .collect(Collectors.groupingBy(WsCardMember::getCardId, Collectors.counting()));
        voList.forEach(vo -> vo.setMemberCount(countMap.getOrDefault(vo.getId(), 0L)));
    }
}
