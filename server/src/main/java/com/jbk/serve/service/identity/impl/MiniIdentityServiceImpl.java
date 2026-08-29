package com.jbk.serve.service.identity.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.device.*;
import com.jbk.serve.mapper.identity.*;
import com.jbk.serve.mapper.product.WsWaterTypeMapper;
import com.jbk.serve.mapper.settlement.*;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.user.*;
import com.jbk.serve.service.identity.IMiniIdentityService;
import com.jbk.serve.service.settlement.IIncomeService;
import com.jbk.tool.consts.identity.IdentityEnum;
import com.jbk.tool.data.device.po.*;
import com.jbk.tool.data.identity.bo.*;
import com.jbk.tool.data.identity.po.*;
import com.jbk.tool.data.identity.vo.*;
import com.jbk.tool.data.product.po.WsWaterType;
import com.jbk.tool.data.settlement.bo.WithdrawBo;
import com.jbk.tool.data.settlement.po.*;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.user.po.*;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 小程序经营身份服务。所有主体与数据范围均从会话 userId 派生，前端不能传 userId 扩大范围。
 * 演示自动审核只改变审核执行者；申请、主体、归属、经营资产和资金流水仍走正式数据库模型。
 */
@Service
@RequiredArgsConstructor
public class MiniIdentityServiceImpl implements IMiniIdentityService {

    private final WsIdentityApplicationMapper applicationMapper;
    private final WsIdentityProfileMapper profileMapper;
    private final WsIdentityAuditMapper auditMapper;
    private final WsInviteCodeMapper inviteCodeMapper;
    private final WsPublicLeadMapper publicLeadMapper;
    private final WsWithdrawOrderMapper withdrawOrderMapper;
    private final WsDemoControlMapper demoControlMapper;
    private final WsUserMapper userMapper;
    private final WsCourierMapper courierMapper;
    private final WsStationMapper stationMapper;
    private final WsDeviceMapper deviceMapper;
    private final WsDeviceOutletMapper outletMapper;
    private final WsQrcodeMapper qrcodeMapper;
    private final WsWaterTypeMapper waterTypeMapper;
    private final WsOwnerReferrerMapper ownerReferrerMapper;
    private final WsOwnerAttributionMapper ownerAttributionMapper;
    private final WsOrderMapper orderMapper;
    private final IIncomeService incomeService;

    @Value("${demo-simulation.enabled:false}")
    private boolean demoMode;

    @Override
    public MiniIdentityOverviewVo overview(Long userId) {
        requireUsableUser(userId);
        Map<Integer, WsIdentityApplication> applications = applicationMapper.selectList(
                        Wrappers.lambdaQuery(WsIdentityApplication.class).eq(WsIdentityApplication::getUserId, userId))
                .stream().collect(Collectors.toMap(WsIdentityApplication::getCapabilityType, Function.identity()));
        WsCourier courier = courierMapper.selectOne(Wrappers.lambdaQuery(WsCourier.class)
                .eq(WsCourier::getUserId, userId).orderByDesc(WsCourier::getId).last("LIMIT 1"));
        MiniIdentityOverviewVo vo = new MiniIdentityOverviewVo().setDemoMode(demoMode);
        vo.getRoles().add(role(IdentityEnum.CapabilityType.COURIER, courier == null ? null : courier.getCourierStatus(),
                courier == null ? null : courier.getCourierName(),
                courier == null ? null : courier.getServiceRegion(),
                courier == null ? null : courier.getCreateTime(),
                courier == null ? null : courier.getAuditRemark(), "D02"));
        vo.getRoles().add(roleOfApplication(IdentityEnum.CapabilityType.OWNER, applications.get(2), "O01"));
        vo.getRoles().add(roleOfApplication(IdentityEnum.CapabilityType.CHANNEL, applications.get(3), "C01"));
        vo.getRoles().add(roleOfApplication(IdentityEnum.CapabilityType.REGION, applications.get(4), "R01"));
        return vo;
    }

    private MiniIdentityOverviewVo.Role roleOfApplication(IdentityEnum.CapabilityType type,
                                                           WsIdentityApplication app, String routeId) {
        return role(type, app == null ? null : app.getApplicationStatus(),
                app == null ? null : app.getApplicantName(), app == null ? null : app.getRegionName(),
                app == null ? null : app.getSubmittedTime(), app == null ? null : app.getReviewRemark(), routeId);
    }

    private MiniIdentityOverviewVo.Role role(IdentityEnum.CapabilityType type, Integer status,
                                              String name, String region, String time, String remark, String routeId) {
        return new MiniIdentityOverviewVo.Role()
                .setCapabilityType(type.getValue()).setTitle(type.getDesc())
                .setStatus(status == null ? 0 : status)
                .setStatusText(statusText(status)).setCapabilityCode(type.getCapabilityCode())
                .setEntryRouteId(routeId).setApplicantName(name).setRegionName(region)
                .setSubmittedTime(time).setReviewRemark(remark);
    }

    private String statusText(Integer status) {
        if (status == null || status == 0) return "未申请";
        for (IdentityEnum.ApplicationStatus item : IdentityEnum.ApplicationStatus.values()) {
            if (item.getValue() == status) return item.getDesc();
        }
        return "状态异常";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MiniIdentityOverviewVo apply(Long userId, MiniIdentityApplyBo bo) {
        WsUser user = requireUsableUser(userId);
        IdentityEnum.CapabilityType type;
        try {
            type = IdentityEnum.CapabilityType.of(bo.getCapabilityType());
        }
        catch (IllegalArgumentException e) {
            throw new JbkException("申请身份不存在");
        }
        if (type == IdentityEnum.CapabilityType.COURIER) {
            throw new JbkException("配送员请使用配送准入申请页");
        }
        validateApplication(type, bo, user);
        WsInviteCode invite = validateInvite(type, bo, userId);
        String now = DateUtils.time();
        WsIdentityApplication existing = applicationMapper.selectOne(Wrappers.lambdaQuery(WsIdentityApplication.class)
                .eq(WsIdentityApplication::getUserId, userId)
                .eq(WsIdentityApplication::getCapabilityType, type.getValue()));
        if (existing != null && Objects.equals(existing.getRequestId(), bo.getRequestId())) {
            return overview(userId);
        }
        if (existing != null && !Objects.equals(existing.getApplicationStatus(),
                IdentityEnum.ApplicationStatus.REJECTED.getValue())) {
            throw new JbkException("该身份已经申请或生效，请勿重复提交");
        }
        int reviewMode = demoMode ? 1 : 2;
        WsIdentityApplication app;
        if (existing == null) {
            app = new WsIdentityApplication().setUserId(userId).setCapabilityType(type.getValue());
        }
        else {
            app = existing;
        }
        app.setApplicationStatus(IdentityEnum.ApplicationStatus.PENDING.getValue())
                .setSubjectType(bo.getSubjectType()).setApplicantName(bo.getApplicantName().trim())
                .setBoundPhone(user.getUserPhone()).setRegionName(bo.getRegionName().trim())
                .setAgentLevel(type == IdentityEnum.CapabilityType.REGION ? bo.getAgentLevel() : null)
                .setInviteCode(trimToNull(bo.getInviteCode())).setStationName(trimToNull(bo.getStationName()))
                .setStationAddress(trimToNull(bo.getStationAddress())).setFormJson("{}")
                .setRequestId(bo.getRequestId()).setReviewMode(reviewMode).setSubmittedTime(now)
                .setReviewedTime(null).setReviewerUserId(null).setReviewRemark("申请已提交");
        if (existing == null) applicationMapper.insert(app); else applicationMapper.updateById(app);
        auditMapper.insert(new WsIdentityAudit().setApplicationId(app.getId()).setFromStatus(
                        existing == null ? null : IdentityEnum.ApplicationStatus.REJECTED.getValue())
                .setToStatus(IdentityEnum.ApplicationStatus.PENDING.getValue()).setActorType(1)
                .setActorUserId(userId).setAuditRemark("申请人提交身份申请并确认合作规范"));
        if (demoMode) {
            approve(app, type, invite, userId, now);
        }
        return overview(userId);
    }

    private void validateApplication(IdentityEnum.CapabilityType type, MiniIdentityApplyBo bo, WsUser user) {
        if (!StringUtils.hasText(user.getUserPhone())) throw new JbkException("申请前请先绑定手机号");
        if (!Objects.equals(bo.getSubjectType(), 1) && !Objects.equals(bo.getSubjectType(), 2)) {
            throw new JbkException("主体类型不合法");
        }
        if (!StringUtils.hasText(bo.getRegionName())) throw new JbkException("经营区域不能为空");
        if (type == IdentityEnum.CapabilityType.OWNER
                && (!StringUtils.hasText(bo.getStationName()) || !StringUtils.hasText(bo.getStationAddress()))) {
            throw new JbkException("机主申请必须填写首个水站名称和地址");
        }
        if ((type == IdentityEnum.CapabilityType.CHANNEL || type == IdentityEnum.CapabilityType.REGION)
                && !StringUtils.hasText(bo.getInviteCode())) {
            throw new JbkException(type.getDesc() + "申请必须填写有效邀请码");
        }
        if (type == IdentityEnum.CapabilityType.REGION
                && (bo.getAgentLevel() == null || bo.getAgentLevel() < 1 || bo.getAgentLevel() > 3)) {
            throw new JbkException("请选择省级、市级或区县级代理");
        }
    }

    private WsInviteCode validateInvite(IdentityEnum.CapabilityType type, MiniIdentityApplyBo bo, Long userId) {
        String codeValue = trimToNull(bo.getInviteCode());
        if (codeValue == null) return null;
        WsInviteCode code = inviteCodeMapper.selectOne(Wrappers.lambdaQuery(WsInviteCode.class)
                .eq(WsInviteCode::getInviteCode, codeValue).eq(WsInviteCode::getCodeStatus, 1));
        if (code == null || !Objects.equals(code.getTargetCapabilityType(), type.getValue())) {
            throw new JbkException("邀请码无效或不适用于当前身份");
        }
        if (code.getExpireTime() != null && code.getExpireTime().compareTo(DateUtils.time()) < 0) {
            throw new JbkException("邀请码已过期");
        }
        if (code.getUseLimit() != null && code.getUseLimit() > 0 && code.getUsedCount() >= code.getUseLimit()) {
            throw new JbkException("邀请码使用次数已满");
        }
        if (StringUtils.hasText(code.getRegionName()) && !code.getRegionName().equals(bo.getRegionName().trim())) {
            throw new JbkException("邀请码与申请区域不一致");
        }
        if (type == IdentityEnum.CapabilityType.REGION && code.getTargetAgentLevel() != null
                && !Objects.equals(code.getTargetAgentLevel(), bo.getAgentLevel())) {
            throw new JbkException("邀请码与申请代理级别不一致");
        }
        if (code.getInviterProfileId() != null) {
            WsIdentityProfile inviter = profileMapper.selectById(code.getInviterProfileId());
            if (inviter == null || !Objects.equals(inviter.getProfileStatus(), 1)) {
                throw new JbkException("邀请主体已停用");
            }
            if (Objects.equals(inviter.getUserId(), userId)) throw new JbkException("不能使用自己生成的邀请码");
        }
        return code;
    }

    private void approve(WsIdentityApplication app, IdentityEnum.CapabilityType type,
                         WsInviteCode invite, Long userId, String now) {
        app.setApplicationStatus(IdentityEnum.ApplicationStatus.APPROVED.getValue())
                .setReviewedTime(now).setReviewerUserId(0L).setReviewRemark("演示环境条件校验通过，自动审核");
        applicationMapper.updateById(app);
        WsIdentityProfile profile = new WsIdentityProfile().setUserId(userId)
                .setCapabilityType(type.getValue()).setProfileStatus(IdentityEnum.ProfileStatus.ENABLED.getValue())
                .setSubjectType(app.getSubjectType()).setSubjectName(app.getApplicantName())
                .setRegionName(app.getRegionName()).setAgentLevel(app.getAgentLevel())
                .setParentProfileId(invite == null ? null : invite.getInviterProfileId())
                .setApplicationId(app.getId());
        profileMapper.insert(profile);
        if (invite != null) consumeInvite(invite);
        if (type == IdentityEnum.CapabilityType.OWNER) {
            approveOwner(profile, app, invite, now);
        }
        else {
            createInvites(profile, type, now);
            if (type == IdentityEnum.CapabilityType.REGION) {
                rebalanceUnassignedPublicLeads(profile.getRegionName(), now);
            }
        }
        auditMapper.insert(new WsIdentityAudit().setApplicationId(app.getId())
                .setFromStatus(IdentityEnum.ApplicationStatus.PENDING.getValue())
                .setToStatus(IdentityEnum.ApplicationStatus.APPROVED.getValue())
                .setActorType(2).setActorUserId(0L).setAuditRemark("演示审核器按准入条件自动通过"));
    }

    private void consumeInvite(WsInviteCode invite) {
        int updated = inviteCodeMapper.update(null, Wrappers.lambdaUpdate(WsInviteCode.class)
                .eq(WsInviteCode::getId, invite.getId()).eq(WsInviteCode::getCodeStatus, 1)
                .eq(WsInviteCode::getUsedCount, invite.getUsedCount())
                .set(WsInviteCode::getUsedCount, invite.getUsedCount() + 1));
        if (updated != 1) throw new JbkException("邀请码刚被其他申请占用，请重试");
    }

    private void createInvites(WsIdentityProfile profile, IdentityEnum.CapabilityType type, String now) {
        if (type == IdentityEnum.CapabilityType.CHANNEL) {
            insertInvite(profile, 2, null, "DK-CH-" + profile.getId() + "-OWN", now);
        }
        if (type == IdentityEnum.CapabilityType.REGION) {
            insertInvite(profile, 2, null, "DK-RG-" + profile.getId() + "-OWN", now);
            insertInvite(profile, 3, null, "DK-RG-" + profile.getId() + "-CH", now);
            if (profile.getAgentLevel() != null && profile.getAgentLevel() < 3) {
                insertInvite(profile, 4, profile.getAgentLevel() + 1,
                        "DK-RG-" + profile.getId() + "-L" + (profile.getAgentLevel() + 1), now);
            }
        }
    }

    private void insertInvite(WsIdentityProfile profile, int target, Integer level, String code, String now) {
        inviteCodeMapper.insert(new WsInviteCode().setInviteCode(code).setInviterProfileId(profile.getId())
                .setTargetCapabilityType(target).setRegionName(profile.getRegionName()).setTargetAgentLevel(level)
                .setUseLimit(0).setUsedCount(0).setExpireTime(null).setCodeStatus(1));
    }

    private void approveOwner(WsIdentityProfile ownerProfile, WsIdentityApplication app,
                              WsInviteCode invite, String now) {
        WsIdentityProfile inviter = invite == null || invite.getInviterProfileId() == null
                ? null : profileMapper.selectById(invite.getInviterProfileId());
        Long channelUserId = inviter != null && Objects.equals(inviter.getCapabilityType(), 3)
                ? inviter.getUserId() : null;
        createOwnerAssets(ownerProfile.getUserId(), channelUserId, app, now);
        if (inviter == null) {
            assignPublicLead(ownerProfile.getUserId(), app, now);
            return;
        }
        bindOwnerRelationships(ownerProfile.getUserId(), inviter, "PRIVATE_REFERRAL", now);
    }

    private void createOwnerAssets(Long userId, Long channelUserId, WsIdentityApplication app, String now) {
        WsStation station = new WsStation().setStationName(app.getStationName())
                .setStationCode("DEMO-ST-" + userId).setStationRegion(app.getRegionName())
                .setStationAddress(app.getStationAddress()).setStationLng("114.3055").setStationLat("30.5928")
                .setStationStatus(1).setOwnerUserId(userId).setChannelUserId(channelUserId)
                .setStationRemark("演示身份申请自动创建；业务数据真实落库");
        stationMapper.insert(station);
        WsDevice device = new WsDevice().setDeviceNo("DEMO-DEV-" + userId)
                .setDeviceName(app.getStationName() + "演示水机").setDeviceModel("DK-DEMO-MQTT")
                .setStationId(station.getId()).setOwnerUserId(userId).setChannelUserId(channelUserId)
                .setFirmwareVersion("demo-1.0").setSimIccid("SIM-DEMO-" + userId)
                .setSimCarrier("演示网络").setSimStatus(1).setOnlineStatus(1).setRunStatus(1)
                .setLastHeartbeat(now).setSignalStrength(-62).setDeviceRemark("MQTT设备模拟器");
        deviceMapper.insert(device);
        WsWaterType water = waterTypeMapper.selectOne(Wrappers.lambdaQuery(WsWaterType.class)
                .eq(WsWaterType::getWaterStatus, 1).orderByDesc(WsWaterType::getDefaultFlag)
                .orderByAsc(WsWaterType::getWaterSort).last("LIMIT 1"));
        if (water == null) throw new JbkException("系统没有可用水种，无法创建演示设备");
        WsDeviceOutlet outlet = new WsDeviceOutlet().setDeviceId(device.getId()).setOutletNo(1)
                .setWaterTypeId(water.getId()).setWaterType(water.getWaterName())
                .setOutletPrice("20").setOutletStatus(1);
        outletMapper.insert(outlet);
        qrcodeMapper.insert(new WsQrcode().setQrcodeContent("DK-DEMO-OWNER-" + userId + "-O1")
                .setQrcodeType(1).setDeviceId(device.getId()).setOutletId(outlet.getId()).setQrcodeStatus(1));
    }

    private void assignPublicLead(Long ownerUserId, WsIdentityApplication app, String now) {
        List<WsIdentityProfile> candidates = profileMapper.selectList(Wrappers.lambdaQuery(WsIdentityProfile.class)
                .eq(WsIdentityProfile::getCapabilityType, 4).eq(WsIdentityProfile::getProfileStatus, 1)
                .eq(WsIdentityProfile::getRegionName, app.getRegionName()).orderByAsc(WsIdentityProfile::getId));
        WsIdentityProfile chosen = leastLoadedProfile(candidates);
        String deadline = LocalDateTime.parse(now, DateUtils.COMPACT_FORMATTER).plusHours(24)
                .format(DateUtils.COMPACT_FORMATTER);
        publicLeadMapper.insert(new WsPublicLead().setOwnerUserId(ownerUserId).setApplicationId(app.getId())
                .setRegionName(app.getRegionName()).setAssignedProfileId(chosen == null ? null : chosen.getId())
                .setLeadStatus(chosen == null ? IdentityEnum.LeadStatus.UNASSIGNED.getValue()
                        : IdentityEnum.LeadStatus.ASSIGNED.getValue())
                .setAssignedTime(chosen == null ? null : now).setResponseDeadline(chosen == null ? null : deadline)
                .setAssignRound(chosen == null ? 0 : 1));
    }

    /**
     * 新区域代理通过时接住同区域历史公域线索。按线索 ID 从旧到新处理，每次选择当前承接量最少者，
     * 同量按 profileId 稳定排序；因此既满足“最久未分配优先”，也不会让后加入代理永远拿不到线索。
     */
    private void rebalanceUnassignedPublicLeads(String regionName, String now) {
        List<WsIdentityProfile> candidates = profileMapper.selectList(Wrappers.lambdaQuery(WsIdentityProfile.class)
                .eq(WsIdentityProfile::getCapabilityType, IdentityEnum.CapabilityType.REGION.getValue())
                .eq(WsIdentityProfile::getProfileStatus, IdentityEnum.ProfileStatus.ENABLED.getValue())
                .eq(WsIdentityProfile::getRegionName, regionName)
                .orderByAsc(WsIdentityProfile::getId));
        if (candidates.isEmpty()) {
            return;
        }
        List<WsPublicLead> leads = publicLeadMapper.selectList(Wrappers.lambdaQuery(WsPublicLead.class)
                .eq(WsPublicLead::getRegionName, regionName)
                .eq(WsPublicLead::getLeadStatus, IdentityEnum.LeadStatus.UNASSIGNED.getValue())
                .isNull(WsPublicLead::getAssignedProfileId)
                .orderByAsc(WsPublicLead::getId));
        String deadline = LocalDateTime.parse(now, DateUtils.COMPACT_FORMATTER).plusHours(24)
                .format(DateUtils.COMPACT_FORMATTER);
        for (WsPublicLead lead : leads) {
            WsIdentityProfile chosen = leastLoadedProfile(candidates);
            int updated = publicLeadMapper.update(null, Wrappers.lambdaUpdate(WsPublicLead.class)
                    .eq(WsPublicLead::getId, lead.getId())
                    .eq(WsPublicLead::getLeadStatus, IdentityEnum.LeadStatus.UNASSIGNED.getValue())
                    .isNull(WsPublicLead::getAssignedProfileId)
                    .set(WsPublicLead::getAssignedProfileId, chosen.getId())
                    .set(WsPublicLead::getLeadStatus, IdentityEnum.LeadStatus.ASSIGNED.getValue())
                    .set(WsPublicLead::getAssignedTime, now)
                    .set(WsPublicLead::getResponseDeadline, deadline)
                    .set(WsPublicLead::getAssignRound,
                            lead.getAssignRound() == null ? 1 : lead.getAssignRound() + 1));
            if (updated != 1) {
                throw new JbkException("公域线索分配状态已变化，请重试");
            }
        }
    }

    private WsIdentityProfile leastLoadedProfile(List<WsIdentityProfile> candidates) {
        return candidates.stream().min(Comparator
                .comparingLong((WsIdentityProfile profile) -> publicLeadMapper.selectCount(
                        Wrappers.lambdaQuery(WsPublicLead.class)
                                .eq(WsPublicLead::getAssignedProfileId, profile.getId())
                                .in(WsPublicLead::getLeadStatus,
                                        IdentityEnum.LeadStatus.ASSIGNED.getValue(),
                                        IdentityEnum.LeadStatus.CONFIRMED.getValue())))
                .thenComparingLong(WsIdentityProfile::getId)).orElseThrow();
    }

    private void bindOwnerRelationships(Long ownerUserId, WsIdentityProfile direct, String source, String now) {
        if (!"PUBLIC_MANUAL".equals(source) && !Objects.equals(ownerUserId, direct.getUserId())
                && ownerReferrerMapper.selectCount(Wrappers.lambdaQuery(WsOwnerReferrer.class)
                .eq(WsOwnerReferrer::getOwnerUserId, ownerUserId)) == 0) {
            ownerReferrerMapper.insert(new WsOwnerReferrer().setOwnerUserId(ownerUserId)
                    .setReferrerUserId(direct.getUserId()).setBindSource("INVITE_LINK")
                    .setBindTime(now).setReferrerRemark("小程序身份申请确认的直接推荐血缘"));
        }
        WsIdentityProfile regional = Objects.equals(direct.getCapabilityType(), 4)
                ? direct : (direct.getParentProfileId() == null ? null : profileMapper.selectById(direct.getParentProfileId()));
        if (regional == null || ownerAttributionMapper.selectCount(Wrappers.lambdaQuery(WsOwnerAttribution.class)
                .eq(WsOwnerAttribution::getOwnerUserId, ownerUserId)) > 0) return;
        Map<Integer, Long> chain = regionalChain(regional);
        ownerAttributionMapper.insert(new WsOwnerAttribution().setOwnerUserId(ownerUserId)
                .setAttributionSource(source).setProvinceAgentUserId(chain.get(1))
                .setCityAgentUserId(chain.get(2)).setCountyAgentUserId(chain.get(3))
                .setBindTime(now).setAttributionRemark("按已确认代理血缘建立；不按设备地理位置漂移"));
    }

    private Map<Integer, Long> regionalChain(WsIdentityProfile start) {
        Map<Integer, Long> chain = new HashMap<>();
        Set<Long> users = new HashSet<>();
        WsIdentityProfile node = start;
        for (int i = 0; i < 3 && node != null; i++) {
            if (!Objects.equals(node.getCapabilityType(), 4) || node.getAgentLevel() == null) break;
            if (!users.add(node.getUserId())) throw new JbkException("同一账号不能占据同一代理血缘多个层级");
            chain.put(node.getAgentLevel(), node.getUserId());
            node = node.getParentProfileId() == null ? null : profileMapper.selectById(node.getParentProfileId());
        }
        return chain;
    }

    @Override
    public MiniIdentityDashboardVo channelDashboard(Long userId) {
        return dashboard(userId, IdentityEnum.CapabilityType.CHANNEL);
    }

    @Override
    public MiniIdentityDashboardVo regionDashboard(Long userId) {
        return dashboard(userId, IdentityEnum.CapabilityType.REGION);
    }

    private MiniIdentityDashboardVo dashboard(Long userId, IdentityEnum.CapabilityType type) {
        WsIdentityProfile profile = requireProfile(userId, type);
        MiniIdentityDashboardVo vo = new MiniIdentityDashboardVo().setCapabilityType(type.getValue())
                .setSubjectName(profile.getSubjectName()).setRegionName(profile.getRegionName())
                .setAgentLevel(profile.getAgentLevel()).setWallet(incomeService.walletFor(userId));
        List<Long> ownerIds;
        if (type == IdentityEnum.CapabilityType.CHANNEL) {
            ownerIds = ownerReferrerMapper.selectList(Wrappers.lambdaQuery(WsOwnerReferrer.class)
                            .eq(WsOwnerReferrer::getReferrerUserId, userId)).stream()
                    .map(WsOwnerReferrer::getOwnerUserId).distinct().toList();
        }
        else {
            ownerIds = ownerAttributionMapper.selectList(Wrappers.lambdaQuery(WsOwnerAttribution.class)
                            .and(q -> q.eq(WsOwnerAttribution::getProvinceAgentUserId, userId)
                                    .or().eq(WsOwnerAttribution::getCityAgentUserId, userId)
                                    .or().eq(WsOwnerAttribution::getCountyAgentUserId, userId))).stream()
                    .map(WsOwnerAttribution::getOwnerUserId).distinct().toList();
        }
        vo.setDirectOwnerCount((long) ownerIds.size());
        List<WsStation> stations = ownerIds.isEmpty() ? List.of() : stationMapper.selectList(
                Wrappers.lambdaQuery(WsStation.class).in(WsStation::getOwnerUserId, ownerIds));
        vo.setStationCount((long) stations.size());
        List<Long> stationIds = stations.stream().map(WsStation::getId).toList();
        vo.setOrderCount(stationIds.isEmpty() ? 0L : orderMapper.selectCount(
                Wrappers.lambdaQuery(WsOrder.class).in(WsOrder::getStationId, stationIds)));
        Map<Long, WsUser> users = ownerIds.isEmpty() ? Map.of() : userMapper.selectBatchIds(ownerIds).stream()
                .collect(Collectors.toMap(WsUser::getId, Function.identity()));
        for (Long ownerId : ownerIds) {
            WsUser owner = users.get(ownerId);
            vo.getOwners().add(new MiniIdentityDashboardVo.Owner().setUserId(String.valueOf(ownerId))
                    .setUserName(owner == null ? "机主" + ownerId : owner.getUserName())
                    .setStationCount(stationMapper.selectCount(Wrappers.lambdaQuery(WsStation.class)
                            .eq(WsStation::getOwnerUserId, ownerId)))
                    .setBindTime(owner == null ? null : owner.getCreateTime()));
        }
        for (WsInviteCode code : inviteCodeMapper.selectList(Wrappers.lambdaQuery(WsInviteCode.class)
                .eq(WsInviteCode::getInviterProfileId, profile.getId()).eq(WsInviteCode::getCodeStatus, 1))) {
            vo.getInviteCodes().add(new MiniIdentityDashboardVo.Invite().setCode(code.getInviteCode())
                    .setTargetCapabilityType(code.getTargetCapabilityType()).setTargetAgentLevel(code.getTargetAgentLevel())
                    .setRegionName(code.getRegionName()).setUsedCount(code.getUsedCount()));
        }
        if (type == IdentityEnum.CapabilityType.REGION) fillPublicLeads(vo, profile);
        fillLineage(vo, profile);
        return vo;
    }

    private void fillPublicLeads(MiniIdentityDashboardVo vo, WsIdentityProfile profile) {
        List<WsPublicLead> leads = publicLeadMapper.selectList(Wrappers.lambdaQuery(WsPublicLead.class)
                .eq(WsPublicLead::getAssignedProfileId, profile.getId()).orderByDesc(WsPublicLead::getId));
        for (WsPublicLead lead : leads) {
            WsUser owner = userMapper.selectById(lead.getOwnerUserId());
            vo.getPublicLeads().add(new MiniIdentityDashboardVo.Lead().setLeadId(String.valueOf(lead.getId()))
                    .setOwnerName(owner == null ? "机主" + lead.getOwnerUserId() : owner.getUserName())
                    .setRegionName(lead.getRegionName()).setStatus(lead.getLeadStatus())
                    .setResponseDeadline(lead.getResponseDeadline()));
        }
    }

    private void fillLineage(MiniIdentityDashboardVo vo, WsIdentityProfile profile) {
        WsIdentityProfile node = profile;
        for (int i = 0; i < 4 && node != null; i++) {
            vo.getLineage().add(new MiniIdentityDashboardVo.Node().setProfileId(String.valueOf(node.getId()))
                    .setSubjectName(node.getSubjectName()).setLevel(node.getAgentLevel())
                    .setRegionName(node.getRegionName()).setCurrent(i == 0));
            node = node.getParentProfileId() == null ? null : profileMapper.selectById(node.getParentProfileId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MiniIdentityDashboardVo confirmPublicLead(Long userId, MiniPublicLeadBo bo) {
        WsIdentityProfile profile = requireProfile(userId, IdentityEnum.CapabilityType.REGION);
        WsPublicLead lead = publicLeadMapper.selectById(bo.getLeadId());
        if (lead == null || !Objects.equals(lead.getAssignedProfileId(), profile.getId())) {
            throw new JbkException("公域线索不存在或不属于当前代理");
        }
        if (Objects.equals(lead.getLeadStatus(), IdentityEnum.LeadStatus.CONFIRMED.getValue())) {
            return regionDashboard(userId);
        }
        if (!Objects.equals(lead.getLeadStatus(), IdentityEnum.LeadStatus.ASSIGNED.getValue())) {
            throw new JbkException("当前线索状态不可确认");
        }
        String now = DateUtils.time();
        int updated = publicLeadMapper.update(null, Wrappers.lambdaUpdate(WsPublicLead.class)
                .eq(WsPublicLead::getId, lead.getId())
                .eq(WsPublicLead::getLeadStatus, IdentityEnum.LeadStatus.ASSIGNED.getValue())
                .set(WsPublicLead::getLeadStatus, IdentityEnum.LeadStatus.CONFIRMED.getValue())
                .set(WsPublicLead::getConfirmedTime, now));
        if (updated != 1) throw new JbkException("线索状态已变化，请刷新");
        bindOwnerRelationships(lead.getOwnerUserId(), profile, "PUBLIC_MANUAL", now);
        return regionDashboard(userId);
    }

    @Override
    public MiniDemoControlVo demoControl(Long userId) {
        requireUsableUser(userId);
        WsDemoControl control = demoControlMapper.selectOne(Wrappers.lambdaQuery(WsDemoControl.class)
                .eq(WsDemoControl::getUserId, userId));
        return controlVo(control);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MiniDemoControlVo updateDemoControl(Long userId, MiniDemoControlBo bo) {
        requireDemo();
        requireUsableUser(userId);
        Set<String> payValues = Set.of("SUCCESS", "CANCEL", "INSUFFICIENT", "TIMEOUT", "DUPLICATE");
        Set<String> deviceValues = Set.of("NORMAL", "SHORT", "REJECT", "TIMEOUT", "DUPLICATE");
        if (!payValues.contains(bo.getNextPayResult()) || !deviceValues.contains(bo.getNextDeviceResult())
                || (bo.getDeliveryAuto() != 1 && bo.getDeliveryAuto() != 2)) {
            throw new JbkException("演示结果选项不合法");
        }
        WsDemoControl control = demoControlMapper.selectOne(Wrappers.lambdaQuery(WsDemoControl.class)
                .eq(WsDemoControl::getUserId, userId));
        if (control == null) {
            control = new WsDemoControl().setUserId(userId);
        }
        control.setNextPayResult(bo.getNextPayResult()).setNextDeviceResult(bo.getNextDeviceResult())
                .setDeliveryAuto(bo.getDeliveryAuto());
        if (control.getId() == null) demoControlMapper.insert(control); else demoControlMapper.updateById(control);
        return controlVo(control);
    }

    private MiniDemoControlVo controlVo(WsDemoControl control) {
        return new MiniDemoControlVo().setDemoMode(demoMode).setFixedQrContent("DK-QR-DEV0001-O1")
                .setNextPayResult(control == null ? "SUCCESS" : control.getNextPayResult())
                .setNextDeviceResult(control == null ? "NORMAL" : control.getNextDeviceResult())
                .setDeliveryAuto(control == null ? 1 : control.getDeliveryAuto())
                .setChannelInviteCode("DK-DEMO-CHANNEL").setRegionProvinceInviteCode("DK-DEMO-REGION-P")
                .setRegionCityInviteCode("DK-DEMO-REGION-C").setRegionCountyInviteCode("DK-DEMO-REGION-D");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String simulateWithdraw(Long userId, WithdrawBo bo) {
        requireDemo();
        if (bo.getAmountFen() == null || bo.getAmountFen() < 100L) throw new JbkException("最低提现金额为1元");
        WsWithdrawOrder existing = withdrawOrderMapper.selectOne(Wrappers.lambdaQuery(WsWithdrawOrder.class)
                .eq(WsWithdrawOrder::getRequestId, bo.getRequestId()));
        if (existing != null) {
            if (!Objects.equals(existing.getUserId(), userId) || !Objects.equals(existing.getAmountFen(), bo.getAmountFen())) {
                throw new JbkException("提现请求号已被其他参数使用");
            }
            return existing.getWithdrawNo();
        }
        String now = DateUtils.time();
        String withdrawNo = "WD" + now + String.format("%06d", Math.floorMod(Objects.hash(userId, bo.getRequestId()), 1_000_000));
        WsWithdrawOrder order = new WsWithdrawOrder().setWithdrawNo(withdrawNo).setRequestId(bo.getRequestId())
                .setUserId(userId).setAmountFen(bo.getAmountFen())
                .setWithdrawStatus(IdentityEnum.WithdrawStatus.PROCESSING.getValue()).setPayoutSource(1)
                .setAppliedTime(now);
        try {
            withdrawOrderMapper.insert(order);
        }
        catch (DuplicateKeyException e) {
            throw new JbkException("提现请求正在处理，请勿重复提交");
        }
        incomeService.applyWithdraw(userId, bo.getAmountFen(), bo.getRequestId());
        incomeService.completeWithdrawSimulated(userId, bo.getAmountFen(), bo.getRequestId());
        order.setWithdrawStatus(IdentityEnum.WithdrawStatus.PAID.getValue()).setFinishedTime(DateUtils.time());
        withdrawOrderMapper.updateById(order);
        return withdrawNo;
    }

    private WsIdentityProfile requireProfile(Long userId, IdentityEnum.CapabilityType type) {
        WsIdentityProfile profile = profileMapper.selectOne(Wrappers.lambdaQuery(WsIdentityProfile.class)
                .eq(WsIdentityProfile::getUserId, userId).eq(WsIdentityProfile::getCapabilityType, type.getValue())
                .eq(WsIdentityProfile::getProfileStatus, IdentityEnum.ProfileStatus.ENABLED.getValue()));
        if (profile == null) throw new JbkException("当前账号未开通" + type.getDesc() + "能力");
        return profile;
    }

    private WsUser requireUsableUser(Long userId) {
        WsUser user = userMapper.selectById(userId);
        if (user == null || !Objects.equals(user.getUserStatus(), 1) || !Objects.equals(user.getDisabledFlag(), 1)) {
            throw new JbkException("账号不存在或已停用");
        }
        return user;
    }

    private void requireDemo() {
        if (!demoMode) throw new JbkException("演示能力未开启");
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
