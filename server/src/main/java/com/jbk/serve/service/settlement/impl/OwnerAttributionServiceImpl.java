package com.jbk.serve.service.settlement.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.settlement.WsOwnerAttributionMapper;
import com.jbk.serve.mapper.settlement.WsOwnerReferrerMapper;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.serve.service.settlement.IOwnerAttributionService;
import com.jbk.tool.consts.settlement.SplitV2Enum;
import com.jbk.tool.data.settlement.bo.OwnerAttributionBo;
import com.jbk.tool.data.settlement.po.WsOwnerAttribution;
import com.jbk.tool.data.settlement.po.WsOwnerReferrer;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 分润归属载体实现。冻结由库层唯一键（一人一行）+ 无更新端点双重担保：
 * 并发重复录入撞唯一键转文案，不做"先查后插"竞态兜底之外的任何覆盖。
 *
 * @author dakang
 * @since 2026-08-14
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerAttributionServiceImpl implements IOwnerAttributionService {

    private final WsOwnerReferrerMapper referrerMapper;
    private final WsOwnerAttributionMapper attributionMapper;
    private final WsUserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createReferrer(OwnerAttributionBo bo, Long opUserId) {
        if (ObjectUtil.hasNull(bo.getOwnerUserId(), bo.getReferrerUserId())) {
            throw new JbkException("机主与推荐人均不能为空");
        }
        if (ObjectUtil.equal(bo.getOwnerUserId(), bo.getReferrerUserId())) {
            // 自荐=自己给自己发 5%，会议口径推荐是「别人招进来」；拒绝而非静默去重
            throw new JbkException("推荐人不能是机主本人");
        }
        requireUserExists(bo.getOwnerUserId(), "机主");
        requireUserExists(bo.getReferrerUserId(), "推荐人");
        WsOwnerReferrer row = new WsOwnerReferrer()
                .setOwnerUserId(bo.getOwnerUserId())
                .setReferrerUserId(bo.getReferrerUserId())
                .setBindSource("ADMIN_ENTRY")
                .setBindTime(DateUtils.time())
                .setReferrerRemark(StrUtil.brief(bo.getRemark(), 200));
        try {
            if (referrerMapper.insert(row) != 1 || row.getId() == null) {
                throw new JbkException("推荐关系写入失败");
            }
        }
        catch (DuplicateKeyException e) {
            throw new JbkException("该机主已有推荐关系（建立即冻结，不可换绑）");
        }
        log.info("机主推荐关系录入：owner={} referrer={} op={}",
                bo.getOwnerUserId(), bo.getReferrerUserId(), opUserId);
        return row.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createAttribution(OwnerAttributionBo bo, Long opUserId) {
        if (bo.getOwnerUserId() == null) {
            throw new JbkException("机主不能为空");
        }
        SplitV2Enum.AttributionSource source = parseSource(bo.getAttributionSource());
        boolean anyAgent = bo.getProvinceAgentUserId() != null || bo.getCityAgentUserId() != null
                || bo.getCountyAgentUserId() != null;
        if (!anyAgent) {
            // 全空链=公域未分配，那是「无行」的语义；插一条三级全空的行会把
            // 「未分配」和「分配了但没人」两种状态混成一种，事后无法区分
            throw new JbkException("三级运营中心至少填一级；公域未分配无需录入");
        }
        requireUserExists(bo.getOwnerUserId(), "机主");
        Set<Long> distinct = new HashSet<>();
        checkAgent(bo.getProvinceAgentUserId(), "省级运营中心", bo.getOwnerUserId(), distinct);
        checkAgent(bo.getCityAgentUserId(), "市级运营中心", bo.getOwnerUserId(), distinct);
        checkAgent(bo.getCountyAgentUserId(), "区县级运营中心", bo.getOwnerUserId(), distinct);

        WsOwnerAttribution row = new WsOwnerAttribution()
                .setOwnerUserId(bo.getOwnerUserId())
                .setAttributionSource(source.name())
                .setProvinceAgentUserId(bo.getProvinceAgentUserId())
                .setCityAgentUserId(bo.getCityAgentUserId())
                .setCountyAgentUserId(bo.getCountyAgentUserId())
                .setBindTime(DateUtils.time())
                .setAttributionRemark(StrUtil.brief(bo.getRemark(), 200));
        try {
            if (attributionMapper.insert(row) != 1 || row.getId() == null) {
                throw new JbkException("区域归属链写入失败");
            }
        }
        catch (DuplicateKeyException e) {
            throw new JbkException("该机主已有区域归属链（建立即冻结）");
        }
        log.info("机主区域归属链录入：owner={} source={} 省={} 市={} 区县={} op={}",
                bo.getOwnerUserId(), source, bo.getProvinceAgentUserId(),
                bo.getCityAgentUserId(), bo.getCountyAgentUserId(), opUserId);
        return row.getId();
    }

    @Override
    public Optional<WsOwnerReferrer> referrerOf(Long ownerUserId) {
        if (ownerUserId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(referrerMapper.selectOne(Wrappers.lambdaQuery(WsOwnerReferrer.class)
                .eq(WsOwnerReferrer::getOwnerUserId, ownerUserId)));
    }

    @Override
    public Optional<WsOwnerAttribution> attributionOf(Long ownerUserId) {
        if (ownerUserId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(attributionMapper.selectOne(Wrappers.lambdaQuery(WsOwnerAttribution.class)
                .eq(WsOwnerAttribution::getOwnerUserId, ownerUserId)));
    }

    private static SplitV2Enum.AttributionSource parseSource(String raw) {
        if (StrUtil.isBlank(raw)) {
            throw new JbkException("归属来源不能为空");
        }
        SplitV2Enum.AttributionSource source;
        try {
            source = SplitV2Enum.AttributionSource.valueOf(raw);
        }
        catch (IllegalArgumentException e) {
            throw new JbkException("归属来源不合法");
        }
        if (source == SplitV2Enum.AttributionSource.PUBLIC_UNASSIGNED) {
            throw new JbkException("公域未分配即「无归属行」，不能作为录入来源");
        }
        return source;
    }

    /**
     * 三级在场者互不相同、且不得是机主本人：同人兼两级会让分账行按(类型,人)聚合时
     * 产生取整漂移并破坏冲减链的份额重算；机主兼自身链上的运营中心=变相自提比例。
     */
    private void checkAgent(Long agentUserId, String label, Long ownerUserId, Set<Long> distinct) {
        if (agentUserId == null) {
            return;
        }
        if (ObjectUtil.equal(agentUserId, ownerUserId)) {
            throw new JbkException(label + "不能是机主本人");
        }
        if (!distinct.add(agentUserId)) {
            throw new JbkException("同一人不得在同一归属链上兼任两级运营中心（" + label + "）");
        }
        requireUserExists(agentUserId, label);
    }

    private void requireUserExists(Long userId, String label) {
        if (userMapper.selectById(userId) == null) {
            throw new JbkException(label + "用户不存在：" + userId);
        }
    }
}
