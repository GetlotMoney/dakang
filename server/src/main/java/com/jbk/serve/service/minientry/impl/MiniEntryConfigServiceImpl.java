package com.jbk.serve.service.minientry.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.minientry.WsMiniEntryConfigMapper;
import com.jbk.serve.service.minientry.IMiniEntryConfigService;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.consts.minientry.MiniEntryEnum;
import com.jbk.tool.data.minientry.po.WsMiniEntryConfig;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * 小程序入口配置实现（S6）。
 *
 * <h3>安全边界</h3>
 * <ul>
 *   <li>内部路由白名单：与 miniapp 路由合同（README 页面编号表）同源的显式集合，
 *       Tabbar 三页（U01/U02/U03）与登录页（C01）刻意不在集合内——固定导航不受配置覆盖。</li>
 *   <li>外链：仅 https；域名必须命中 {@code mini.entry.external-domain-whitelist}
 *       （默认空=全部拒绝，甲方批准域名前不放行任何外链）；javascript:/data:/
 *       任意 scheme 在 URI 解析层直接拒绝。</li>
 *   <li>并发发布：VERSION CAS，同一行只有一个流转赢家；同键唯一由库层
 *       uk_mini_entry_key 兜底。</li>
 * </ul>
 */
@Slf4j
@Service
public class MiniEntryConfigServiceImpl
        extends ServiceImpl<WsMiniEntryConfigMapper, WsMiniEntryConfig>
        implements IMiniEntryConfigService {

    /**
     * 可配置的功能入口路由白名单：与 miniapp/README 页面编号表同源（非 Tabbar、
     * 非登录、非流程中间页的可直达入口页）。新增页面须同步登记，漏登记=不可配置
     * （fail-closed，宁可配不了也不放行未知路由）。
     */
    private static final Set<String> ROUTE_WHITELIST = Set.of(
            "U07", "U08", "U10", "U13", "U14", "U16",
            "O01", "O06", "D01", "D02", "C02");

    private static final Set<String> CONTENT_KEYS = Set.of("protocol", "faq", "service", "notice");

    @Value("${mini.entry.external-domain-whitelist:}")
    private String externalDomainWhitelist;

    @Override
    public List<WsMiniEntryConfig> listForAdmin() {
        return list(Wrappers.lambdaQuery(WsMiniEntryConfig.class)
                .orderByAsc(WsMiniEntryConfig::getSortNo)
                .orderByAsc(WsMiniEntryConfig::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveDraft(WsMiniEntryConfig bo, Integer expectedVersion) {
        validateShape(bo);
        String now = DateUtils.time();
        if (bo.getId() == null) {
            WsMiniEntryConfig fresh = new WsMiniEntryConfig()
                    .setEntryKey(bo.getEntryKey())
                    .setEntryType(bo.getEntryType())
                    .setEntryName(bo.getEntryName())
                    .setSortNo(bo.getSortNo() == null ? 0 : bo.getSortNo())
                    .setEnabledFlag(bo.getEnabledFlag() == null
                            ? ApiEnum.Flag.YES.value() : bo.getEnabledFlag())
                    .setJumpType(bo.getJumpType())
                    .setRouteId(bo.getRouteId())
                    .setExternalUrl(bo.getExternalUrl())
                    .setContentText(bo.getContentText())
                    .setConfigStatus(MiniEntryEnum.ConfigStatus.DRAFT.getValue())
                    .setVersion(1);
            try {
                save(fresh);
            }
            catch (DuplicateKeyException e) {
                throw new JbkException("入口键已存在，同一入口只保留一条配置");
            }
            return fresh.getId();
        }
        if (expectedVersion == null) {
            throw new JbkException("配置版本缺失，请刷新后重试");
        }
        // 已发布不可直改（先撤回再改）：修改与状态流转共用 VERSION CAS
        int updated = baseMapper.update(null, Wrappers.lambdaUpdate(WsMiniEntryConfig.class)
                .eq(WsMiniEntryConfig::getId, bo.getId())
                .eq(WsMiniEntryConfig::getVersion, expectedVersion)
                .in(WsMiniEntryConfig::getConfigStatus,
                        MiniEntryEnum.ConfigStatus.DRAFT.getValue(),
                        MiniEntryEnum.ConfigStatus.RETRACTED.getValue())
                .set(WsMiniEntryConfig::getEntryName, bo.getEntryName())
                .set(WsMiniEntryConfig::getSortNo, bo.getSortNo() == null ? 0 : bo.getSortNo())
                .set(WsMiniEntryConfig::getEnabledFlag, bo.getEnabledFlag() == null
                        ? ApiEnum.Flag.YES.value() : bo.getEnabledFlag())
                .set(WsMiniEntryConfig::getJumpType, bo.getJumpType())
                .set(WsMiniEntryConfig::getRouteId, bo.getRouteId())
                .set(WsMiniEntryConfig::getExternalUrl, bo.getExternalUrl())
                .set(WsMiniEntryConfig::getContentText, bo.getContentText())
                .set(WsMiniEntryConfig::getConfigStatus, MiniEntryEnum.ConfigStatus.DRAFT.getValue())
                .set(WsMiniEntryConfig::getUpdateTime, now)
                .set(WsMiniEntryConfig::getVersion, expectedVersion + 1));
        if (updated != 1) {
            throw new JbkException("配置已被其他操作变更或处于已发布状态，请刷新后重试");
        }
        return bo.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long id, Integer expectedVersion) {
        requireIdVersion(id, expectedVersion);
        WsMiniEntryConfig current = getById(id);
        if (ObjectUtil.isNotNull(current) && ObjectUtil.equal(current.getEntryType(),
                MiniEntryEnum.EntryType.CONTENT_LINK.getValue())) {
            // R1-6：存量内容链接行禁止发布（端上无消费入口，发布即虚假可见性声明）
            throw new JbkException("内容链接暂不支持发布");
        }
        int updated = baseMapper.update(null, Wrappers.lambdaUpdate(WsMiniEntryConfig.class)
                .eq(WsMiniEntryConfig::getId, id)
                .eq(WsMiniEntryConfig::getVersion, expectedVersion)
                .in(WsMiniEntryConfig::getConfigStatus,
                        MiniEntryEnum.ConfigStatus.DRAFT.getValue(),
                        MiniEntryEnum.ConfigStatus.RETRACTED.getValue())
                .set(WsMiniEntryConfig::getConfigStatus, MiniEntryEnum.ConfigStatus.PUBLISHED.getValue())
                .set(WsMiniEntryConfig::getPublishTime, DateUtils.time())
                .set(WsMiniEntryConfig::getVersion, expectedVersion + 1));
        if (updated != 1) {
            throw new JbkException("发布未生效：配置已被其他操作变更，请刷新后重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retract(Long id, Integer expectedVersion) {
        requireIdVersion(id, expectedVersion);
        int updated = baseMapper.update(null, Wrappers.lambdaUpdate(WsMiniEntryConfig.class)
                .eq(WsMiniEntryConfig::getId, id)
                .eq(WsMiniEntryConfig::getVersion, expectedVersion)
                .eq(WsMiniEntryConfig::getConfigStatus, MiniEntryEnum.ConfigStatus.PUBLISHED.getValue())
                .set(WsMiniEntryConfig::getConfigStatus, MiniEntryEnum.ConfigStatus.RETRACTED.getValue())
                .set(WsMiniEntryConfig::getVersion, expectedVersion + 1));
        if (updated != 1) {
            throw new JbkException("撤回未生效：仅已发布配置可撤回，请刷新后重试");
        }
    }

    @Override
    public List<WsMiniEntryConfig> listPublished() {
        return list(Wrappers.lambdaQuery(WsMiniEntryConfig.class)
                .eq(WsMiniEntryConfig::getConfigStatus, MiniEntryEnum.ConfigStatus.PUBLISHED.getValue())
                .eq(WsMiniEntryConfig::getEnabledFlag, ApiEnum.Flag.YES.value())
                .orderByAsc(WsMiniEntryConfig::getSortNo)
                .orderByAsc(WsMiniEntryConfig::getId));
    }

    private void validateShape(WsMiniEntryConfig bo) {
        if (StrUtil.isBlank(bo.getEntryKey()) || bo.getEntryKey().length() > 50) {
            throw new JbkException("入口键不合法");
        }
        if (StrUtil.isBlank(bo.getEntryName()) || bo.getEntryName().length() > 50) {
            throw new JbkException("展示名称不合法");
        }
        Integer type = bo.getEntryType();
        if (ObjectUtil.equal(type, MiniEntryEnum.EntryType.FEATURE.getValue())) {
            if (ObjectUtil.notEqual(bo.getJumpType(), MiniEntryEnum.JumpType.INTERNAL_ROUTE.getValue())) {
                throw new JbkException("功能入口只允许内部路由跳转");
            }
            if (!ROUTE_WHITELIST.contains(bo.getRouteId())) {
                throw new JbkException("路由编号不在可配置白名单内");
            }
            if (!bo.getEntryKey().equals(bo.getRouteId())) {
                throw new JbkException("功能入口的入口键必须与路由编号一致");
            }
        }
        else if (ObjectUtil.equal(type, MiniEntryEnum.EntryType.CONTENT_LINK.getValue())) {
            // R1-6 最小安全收口：小程序端没有内容链接的消费与跳转入口，「发布后即刻可见」
            // 不成立——新增与发布一律拒绝；存量 type=2 行只允许只读/撤回。真正实现须另开
            // 切片（小程序可见入口+跳转页+微信业务域名验证）后再放开
            throw new JbkException("内容链接暂不支持配置");
        }
        else if (ObjectUtil.equal(type, MiniEntryEnum.EntryType.NOTICE.getValue())) {
            if (StrUtil.isBlank(bo.getContentText()) || bo.getContentText().length() > 500) {
                throw new JbkException("维护公告必须填写正文（500 字以内）");
            }
        }
        else {
            throw new JbkException("入口类型不合法");
        }
    }

    /** 外链闸：仅 https + 域名白名单（默认空=全拒绝）；javascript:/data:/相对地址一律拒绝。 */
    private void requireWhitelistedHttpsUrl(String url) {
        if (StrUtil.isBlank(url) || url.length() > 500) {
            throw new JbkException("外部链接不合法");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        }
        catch (IllegalArgumentException e) {
            throw new JbkException("外部链接不是合法地址");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new JbkException("外部链接仅允许 https 协议");
        }
        String host = uri.getHost();
        if (StrUtil.isBlank(host)) {
            throw new JbkException("外部链接缺少域名");
        }
        Set<String> allowed = StrUtil.isBlank(externalDomainWhitelist)
                ? Set.of()
                : Set.of(externalDomainWhitelist.split(","));
        boolean hit = allowed.stream().map(String::trim).filter(StrUtil::isNotBlank)
                .anyMatch(domain -> host.equalsIgnoreCase(domain)
                        || host.toLowerCase().endsWith("." + domain.toLowerCase()));
        if (!hit) {
            throw new JbkException("外部链接域名未获批准，暂不可配置");
        }
    }

    private static void requireIdVersion(Long id, Integer expectedVersion) {
        if (id == null || id <= 0 || expectedVersion == null) {
            throw new JbkException("配置标识或版本缺失，请刷新后重试");
        }
    }
}
