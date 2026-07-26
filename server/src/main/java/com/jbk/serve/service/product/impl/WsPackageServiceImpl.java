package com.jbk.serve.service.product.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.product.WsPackageMapper;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.mini.recharge.RechargeLimits;
import com.jbk.serve.service.product.IWsPackageService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.product.bo.WsPackageBo;
import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.data.product.vo.WsPackageVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.OptionalUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 水卡套餐后台服务实现
 *
 * <p>三条不变式：</p>
 * <ol>
 *   <li>数值域唯一规则入口是 {@link RechargeLimits#validatePackage}——小程序创单与后台配置
 *       共用同一份上限，防止“后台能配出创单被拒的套餐”；</li>
 *   <li>范围唯一解析器是 {@link WaterCardScope}——非空必须 normalize 通过并落规范化指纹，
 *       空=未配置（L2-A6：首次购卡不可购；L2-M6：充值不加限制），存 NULL 不存空串；</li>
 *   <li>单价快照由服务端按 售价÷水量 派生（分/升，两位小数），不信任前端计算——
 *       它是退款折算与对账依据，口径漂移即资金口径漂移。</li>
 * </ol>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Service
@RequiredArgsConstructor
public class WsPackageServiceImpl implements IWsPackageService {

    /** 套餐状态(1330)：1 在售 2 下架。 */
    private static final int STATUS_ON_SALE = 1;
    private static final int STATUS_OFF_SHELF = 2;

    private final WsPackageMapper wsPackageMapper;

    @Override
    public PageDataVo<WsPackageVo> pageData(WsPackageBo packageBo) {
        // 新配置的套餐排前面，便于运营确认刚保存的结果（DATA_STATUS 由 @TableLogic 自动过滤）
        Page<WsPackage> page = wsPackageMapper.selectPage(new Page<>(packageBo.getCurrent(), packageBo.getSize()),
                Wrappers.lambdaQuery(WsPackage.class)
                        .like(StrUtil.isNotBlank(packageBo.getPackageName()), WsPackage::getPackageName, packageBo.getPackageName())
                        .eq(ObjectUtil.isNotNull(packageBo.getPackageStatus()), WsPackage::getPackageStatus, packageBo.getPackageStatus())
                        .orderByDesc(WsPackage::getId));
        List<WsPackageVo> voList = page.getRecords().stream().map(WsPackageServiceImpl::toVo).toList();
        return PageDataVo.getPageData(voList, page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveData(WsPackageBo packageBo) {
        requireShelfStatus(packageBo.getPackageStatus(), "套餐状态非法");
        WsPackage pkg = buildValidated(packageBo);
        pkg.setPackageStatus(packageBo.getPackageStatus());
        wsPackageMapper.insert(pkg);
        return pkg.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateData(WsPackageBo packageBo) {
        WsPackage exist = wsPackageMapper.selectById(packageBo.getId());
        OptionalUtils.nullToElseThrow(exist, "套餐不存在");
        WsPackage pkg = buildValidated(packageBo);

        // EXPIRE_DAYS/SCOPE_JSON/PACKAGE_REMARK 允许被清空（有限→永久、清范围、清备注），
        // 而 update(entity) 不写 null 列，因此这三列必须经 wrapper 显式 set；
        // 先从 entity 摘出避免与 wrapper 重复出现在 SET 子句。
        Integer expireDays = pkg.getExpireDays();
        String scopeJson = pkg.getScopeJson();
        String remark = pkg.getPackageRemark();
        pkg.setExpireDays(null);
        pkg.setScopeJson(null);
        pkg.setPackageRemark(null);
        // 状态不随修改变化：上下架是独立权限（product:package:shelf）+ CAS 通道，
        // 若允许 update 顺带改状态，仅有 update 权限的账号就能绕过上下架权限。
        pkg.setPackageStatus(null);

        int rows = wsPackageMapper.update(pkg, Wrappers.lambdaUpdate(WsPackage.class)
                .set(WsPackage::getExpireDays, expireDays)
                .set(WsPackage::getScopeJson, scopeJson)
                .set(WsPackage::getPackageRemark, remark)
                .eq(WsPackage::getId, packageBo.getId()));
        if (rows != 1) {
            // 前置存在性校验已过仍更新 0 行=并发删除，明确报错而不是静默成功
            throw new JbkException("套餐更新失败，请刷新后重试");
        }
        return Boolean.TRUE;
    }

    @Override
    public Boolean shelfData(WsPackageBo packageBo) {
        Integer target = packageBo.getTargetStatus();
        requireShelfStatus(target, "套餐目标状态非法");
        WsPackage exist = wsPackageMapper.selectById(packageBo.getId());
        OptionalUtils.nullToElseThrow(exist, "套餐不存在");

        // CAS：前置状态精确匹配（在售↔下架互为前置），并发重复点击时 affected=0 直接报错，
        // 不做“先读后写”的覆盖。历史订单不受影响：下单时已冻结 PACKAGE_SNAP。
        int expectedFrom = target == STATUS_ON_SALE ? STATUS_OFF_SHELF : STATUS_ON_SALE;
        WsPackage patch = new WsPackage();
        patch.setPackageStatus(target);
        int rows = wsPackageMapper.update(patch, Wrappers.lambdaUpdate(WsPackage.class)
                .eq(WsPackage::getId, packageBo.getId())
                .eq(WsPackage::getPackageStatus, expectedFrom));
        if (rows != 1) {
            throw new JbkException("套餐状态已变化，请刷新后重试");
        }
        return Boolean.TRUE;
    }

    /**
     * 组装并校验套餐（新增/修改共用）：trim 名称、bonus 缺省 0、服务端派生单价快照，
     * 再依次过 RechargeLimits（数值域）与 WaterCardScope（范围）。不设置状态列。
     */
    private static WsPackage buildValidated(WsPackageBo bo) {
        WsPackage pkg = new WsPackage();
        pkg.setPackageName(bo.getPackageName() == null ? null : bo.getPackageName().trim());
        pkg.setPayAmount(bo.getPayAmount());
        pkg.setWaterMl(bo.getWaterMl());
        pkg.setBonusAmount(bo.getBonusAmount() == null ? 0L : bo.getBonusAmount());
        pkg.setExpireDays(bo.getExpireDays());
        pkg.setPackageRemark(StrUtil.isBlank(bo.getPackageRemark()) ? null : bo.getPackageRemark().trim());
        pkg.setUnitPriceSnap(deriveUnitPriceSnap(bo.getPayAmount(), bo.getWaterMl()));
        RechargeLimits.validatePackage(pkg);
        pkg.setScopeJson(normalizeScopeOrNull(bo.getScopeJson()));
        return pkg;
    }

    /**
     * 单价快照（分/升）＝ 售价(分)×1000÷水量(毫升)，HALF_UP 保留两位；纯金额套餐固定 "0"。
     * 极端组合四舍五入到 0.00 时由 RechargeLimits 的“水量套餐单价必须大于 0”拒绝（fail-closed）。
     */
    private static String deriveUnitPriceSnap(Long payAmount, Long waterMl) {
        if (payAmount == null || waterMl == null || waterMl <= 0 || payAmount <= 0) {
            return "0";
        }
        return BigDecimal.valueOf(payAmount)
                .multiply(BigDecimal.valueOf(1000))
                .divide(BigDecimal.valueOf(waterMl), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** 空=未配置存 NULL；非空必须 normalize 通过并落规范化指纹（展示名不落库，编辑时由前端按 ID 回查）。 */
    private static String normalizeScopeOrNull(String scopeJson) {
        if (StrUtil.isBlank(scopeJson)) {
            return null;
        }
        return WaterCardScope.normalize(scopeJson, "套餐").toCanonicalJson().toJSONString();
    }

    private static void requireShelfStatus(Integer status, String message) {
        if (status == null || (status != STATUS_ON_SALE && status != STATUS_OFF_SHELF)) {
            throw new JbkException(message);
        }
    }

    /** Po→Vo：范围按唯一解析器规范化后下发（不下发原文）；非法配置不炸列表，降级为 scopeValid=false。 */
    private static WsPackageVo toVo(WsPackage po) {
        WsPackageVo vo = new WsPackageVo();
        vo.setId(po.getId());
        vo.setPackageName(po.getPackageName());
        vo.setPayAmount(po.getPayAmount());
        vo.setWaterMl(po.getWaterMl());
        vo.setBonusAmount(po.getBonusAmount());
        vo.setUnitPriceSnap(po.getUnitPriceSnap());
        vo.setExpireDays(po.getExpireDays());
        vo.setPackageStatus(po.getPackageStatus());
        vo.setPackageRemark(po.getPackageRemark());
        vo.setCreateTime(po.getCreateTime());
        vo.setUpdateTime(po.getUpdateTime());
        if (StrUtil.isBlank(po.getScopeJson())) {
            vo.setScopeValid(Boolean.FALSE);
            vo.setScopeSummary("未配置（首次购卡不可选，充值不加限制）");
            return vo;
        }
        try {
            WaterCardScope scope = WaterCardScope.normalize(po.getScopeJson(), "套餐");
            vo.setScopeType(scope.scopeType());
            vo.setStationIds(scope.stationIds());
            vo.setDeviceIds(scope.deviceIds());
            vo.setOutletIds(scope.outletIds());
            vo.setScopeValid(Boolean.TRUE);
            vo.setScopeSummary(scope.description());
        } catch (JbkException invalidScope) {
            vo.setScopeValid(Boolean.FALSE);
            vo.setScopeSummary("配置非法（默认拒绝，请重新设置范围）");
        }
        return vo;
    }
}
