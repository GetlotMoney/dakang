package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.mall.WsMallWarehouseMapper;
import com.jbk.serve.service.mall.IMallWarehouseService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallIdBo;
import com.jbk.tool.data.mall.bo.MallQueryBo;
import com.jbk.tool.data.mall.bo.MallStatusChangeBo;
import com.jbk.tool.data.mall.bo.MallWarehouseBo;
import com.jbk.tool.data.mall.po.WsMallWarehouse;
import com.jbk.tool.data.mall.vo.MallWarehouseVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.PhoneMask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 商城前置仓服务实现（E2E-09 S1）。
 *
 * <p>SERVICE_SCOPE_JSON 由 {@link #buildScopeJson} 唯一构造与校验（结构化行政区
 * 编码，六位数字、非空、去重）；联系电话出参恒经 PhoneMask 脱敏——原文只存库，
 * 不出任何接口。</p>
 */
@Slf4j
@Service
public class MallWarehouseServiceImpl implements IMallWarehouseService {

    @Autowired
    private WsMallWarehouseMapper warehouseMapper;
    @Autowired
    private IWsDomainEventService domainEventService;

    @Override
    public PageDataVo<MallWarehouseVo> page(MallQueryBo bo) {
        Page<WsMallWarehouse> page = warehouseMapper.selectPage(
                new Page<>(bo.getCurrent() == null ? 1 : Math.max(1, bo.getCurrent()),
                        bo.getSize() == null ? 10 : Math.min(Math.max(1, bo.getSize()), 100)),
                Wrappers.lambdaQuery(WsMallWarehouse.class)
                        .eq(bo.getStatus() != null, WsMallWarehouse::getWarehouseStatus, bo.getStatus())
                        .and(ObjectUtil.isNotEmpty(bo.getKeyword()), q -> q
                                .like(WsMallWarehouse::getWarehouseName, bo.getKeyword())
                                .or().like(WsMallWarehouse::getWarehouseNo, bo.getKeyword()))
                        .orderByDesc(WsMallWarehouse::getId));
        List<MallWarehouseVo> rows = page.getRecords().stream()
                .map(MallWarehouseServiceImpl::voOf).toList();
        return new PageDataVo<>(rows, page.getTotal());
    }

    @Override
    public MallWarehouseVo detail(MallIdBo bo) {
        WsMallWarehouse row = warehouseMapper.selectById(bo.getId());
        if (ObjectUtil.isNull(row)) {
            throw new JbkException("前置仓不存在");
        }
        return voOf(row);
    }

    @Override
    public List<MallWarehouseVo> listAll() {
        return warehouseMapper.selectList(Wrappers.lambdaQuery(WsMallWarehouse.class)
                        .orderByAsc(WsMallWarehouse::getId))
                .stream().map(MallWarehouseServiceImpl::voOf).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long save(MallWarehouseBo bo) {
        if (ObjectUtil.isEmpty(bo.getWarehouseNo())) {
            throw new JbkException("请填写仓库编号");
        }
        WsMallWarehouse row = new WsMallWarehouse()
                .setWarehouseNo(bo.getWarehouseNo().trim())
                .setWarehouseName(bo.getWarehouseName().trim())
                .setContactName(bo.getContactName().trim())
                .setContactPhone(bo.getContactPhone().trim())
                .setProvinceCode(bo.getProvinceCode())
                .setCityCode(bo.getCityCode())
                .setDistrictCode(bo.getDistrictCode())
                .setWarehouseAddress(bo.getWarehouseAddress().trim())
                .setLongitude(bo.getLongitude())
                .setLatitude(bo.getLatitude())
                .setServiceScopeJson(buildScopeJson(bo.getDistrictCodes()))
                .setWarehouseStatus(MallEnum.WarehouseStatus.ENABLED.getValue())
                .setVersion(1);
        try {
            warehouseMapper.insert(row);
        }
        catch (DuplicateKeyException e) {
            throw new JbkException("仓库编号已存在（含历史已删除），请更换");
        }
        return row.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(MallWarehouseBo bo) {
        if (bo.getId() == null) {
            throw new JbkException("缺少仓库信息");
        }
        WsMallWarehouse existed = warehouseMapper.selectById(bo.getId());
        if (ObjectUtil.isNull(existed)) {
            throw new JbkException("前置仓不存在");
        }
        // 编号不可改；状态不在此改（启停走 CAS 端点）
        int updated = warehouseMapper.update(null, Wrappers.lambdaUpdate(WsMallWarehouse.class)
                .eq(WsMallWarehouse::getId, bo.getId())
                .set(WsMallWarehouse::getWarehouseName, bo.getWarehouseName().trim())
                .set(WsMallWarehouse::getContactName, bo.getContactName().trim())
                .set(WsMallWarehouse::getContactPhone, bo.getContactPhone().trim())
                .set(WsMallWarehouse::getProvinceCode, bo.getProvinceCode())
                .set(WsMallWarehouse::getCityCode, bo.getCityCode())
                .set(WsMallWarehouse::getDistrictCode, bo.getDistrictCode())
                .set(WsMallWarehouse::getWarehouseAddress, bo.getWarehouseAddress().trim())
                .set(WsMallWarehouse::getLongitude, bo.getLongitude())
                .set(WsMallWarehouse::getLatitude, bo.getLatitude())
                .set(WsMallWarehouse::getServiceScopeJson, buildScopeJson(bo.getDistrictCodes())));
        if (updated != 1) {
            throw new JbkException("前置仓更新失败，请刷新后重试");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean changeStatus(MallStatusChangeBo bo) {
        if (ObjectUtil.notEqual(bo.getTargetStatus(), MallEnum.WarehouseStatus.ENABLED.getValue())
                && ObjectUtil.notEqual(bo.getTargetStatus(), MallEnum.WarehouseStatus.DISABLED.getValue())) {
            throw new JbkException("目标状态非法");
        }
        if (bo.getVersion() == null) {
            throw new JbkException("缺少版本信息，请刷新后重试");
        }
        WsMallWarehouse existed = warehouseMapper.selectById(bo.getId());
        if (ObjectUtil.isNull(existed)) {
            throw new JbkException("前置仓不存在");
        }
        if (ObjectUtil.equal(existed.getWarehouseStatus(), bo.getTargetStatus())) {
            return true;
        }
        int updated = warehouseMapper.update(null, Wrappers.lambdaUpdate(WsMallWarehouse.class)
                .eq(WsMallWarehouse::getId, bo.getId())
                .eq(WsMallWarehouse::getVersion, bo.getVersion())
                .eq(WsMallWarehouse::getWarehouseStatus, existed.getWarehouseStatus())
                .set(WsMallWarehouse::getWarehouseStatus, bo.getTargetStatus())
                .set(WsMallWarehouse::getVersion, bo.getVersion() + 1));
        if (updated != 1) {
            throw new JbkException("前置仓状态已变化，请刷新后重试");
        }
        // 启停改变小程序可售聚合口径：可靠审计与状态迁移同事务
        domainEventService.recordReliableInTx(OpsEnum.EventType.MALL,
                "MALL_WAREHOUSE:" + existed.getId(),
                statusDesc(existed.getWarehouseStatus()), statusDesc(bo.getTargetStatus()));
        return true;
    }

    private static String statusDesc(Integer status) {
        return ObjectUtil.equal(status, MallEnum.WarehouseStatus.ENABLED.getValue()) ? "启用"
                : ObjectUtil.equal(status, MallEnum.WarehouseStatus.DISABLED.getValue()) ? "停用"
                : "未知" + status;
    }

    // ==================== 履约范围（唯一构造/校验入口） ====================

    /** 行政区码集 → SERVICE_SCOPE_JSON：六位数字、非空、去重；结构恒 districts 型。 */
    static String buildScopeJson(List<String> districtCodes) {
        if (districtCodes == null || districtCodes.isEmpty()) {
            throw new JbkException("请至少配置一个履约行政区");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String code : districtCodes) {
            String trimmed = code == null ? "" : code.trim();
            if (!trimmed.matches("^\\d{6}$")) {
                throw new JbkException("履约行政区码必须为 6 位数字：" + code);
            }
            normalized.add(trimmed);
        }
        JSONObject scope = JSONUtil.createObj()
                .set("scopeType", "districts")
                .set("districtCodes", new ArrayList<>(normalized));
        return scope.toString();
    }

    /** SERVICE_SCOPE_JSON → 行政区码集（历史异常原文回空集，管理页提示重新配置）。 */
    static List<String> parseScopeDistricts(String scopeJson) {
        List<String> codes = new ArrayList<>();
        if (ObjectUtil.isEmpty(scopeJson)) {
            return codes;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(scopeJson);
            if (!"districts".equals(obj.getStr("scopeType"))) {
                return codes;
            }
            JSONArray array = obj.getJSONArray("districtCodes");
            if (array != null) {
                for (Object item : array) {
                    if (item != null && String.valueOf(item).matches("^\\d{6}$")) {
                        codes.add(String.valueOf(item));
                    }
                }
            }
        }
        catch (Exception e) {
            log.warn("前置仓范围 JSON 不可解析，按空范围展示：{}", scopeJson);
        }
        return codes;
    }

    static MallWarehouseVo voOf(WsMallWarehouse row) {
        return new MallWarehouseVo()
                .setId(String.valueOf(row.getId()))
                .setWarehouseNo(row.getWarehouseNo())
                .setWarehouseName(row.getWarehouseName())
                .setContactName(row.getContactName())
                // 脱敏唯一实现：非 11 位整体屏蔽，绝不回落明文
                .setMaskedPhone(PhoneMask.mask(row.getContactPhone()))
                .setProvinceCode(row.getProvinceCode())
                .setCityCode(row.getCityCode())
                .setDistrictCode(row.getDistrictCode())
                .setWarehouseAddress(row.getWarehouseAddress())
                .setLongitude(row.getLongitude())
                .setLatitude(row.getLatitude())
                .setDistrictCodes(parseScopeDistricts(row.getServiceScopeJson()))
                .setWarehouseStatus(row.getWarehouseStatus())
                .setVersion(row.getVersion())
                .setCreateTime(row.getCreateTime());
    }
}
