package com.jbk.serve.service.product.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.product.WsWaterTypeMapper;
import com.jbk.serve.service.product.IWsWaterTypeService;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.product.bo.WsWaterTypeBo;
import com.jbk.tool.data.product.po.WsWaterType;
import com.jbk.tool.data.product.vo.WsWaterTypeVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.OptionalUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 水种字典服务实现
 * <p>约束：全局仅一个默认水种（代码层保证）；默认水种不允许禁用；
 * 名称业务唯一（出水口 WATER_TYPE 名称冗余按其回填）；删除须先禁用且无出水口引用。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Service
public class WsWaterTypeServiceImpl extends ServiceImpl<WsWaterTypeMapper, WsWaterType> implements IWsWaterTypeService {

    /** 跨域引用校验走 Mapper，避免与设备域服务互相注入成环 */
    @Autowired
    private WsDeviceOutletMapper outletMapper;

    @Override
    public PageDataVo<WsWaterTypeVo> pageData(WsWaterTypeBo waterTypeBo) {
        Page<WsWaterType> page = page(new Page<>(waterTypeBo.getCurrent(), waterTypeBo.getSize()),
                Wrappers.lambdaQuery(WsWaterType.class)
                        .like(StrUtil.isNotBlank(waterTypeBo.getWaterName()), WsWaterType::getWaterName, waterTypeBo.getWaterName())
                        .eq(ObjectUtil.isNotNull(waterTypeBo.getWaterStatus()), WsWaterType::getWaterStatus, waterTypeBo.getWaterStatus())
                        .orderByAsc(WsWaterType::getWaterSort));
        List<WsWaterTypeVo> voList = page.getRecords().stream()
                .map(e -> BeanUtil.copyProperties(e, WsWaterTypeVo.class))
                .collect(Collectors.toList());
        fillOutletRefCount(voList);
        return PageDataVo.getPageData(voList, page.getTotal());
    }

    @Override
    public List<WsWaterTypeVo> listEnabled() {
        List<WsWaterType> list = list(Wrappers.lambdaQuery(WsWaterType.class)
                .eq(WsWaterType::getWaterStatus, ApiEnum.DisabledFlag.NORMAL.getValue())
                .orderByAsc(WsWaterType::getWaterSort));
        return list.stream().map(e -> BeanUtil.copyProperties(e, WsWaterTypeVo.class)).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveData(WsWaterTypeBo waterTypeBo) {
        checkNameUnique(waterTypeBo.getWaterName(), null);
        checkDefaultNotDisabled(waterTypeBo);
        WsWaterType waterType = BeanUtil.copyProperties(waterTypeBo, WsWaterType.class);
        if (isDefault(waterTypeBo.getDefaultFlag())) {
            clearOtherDefault(null);
        }
        save(waterType);
        return waterType.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateData(WsWaterTypeBo waterTypeBo) {
        WsWaterType exist = getById(waterTypeBo.getId());
        OptionalUtils.nullToElseThrow(exist, "水种不存在");
        checkNameUnique(waterTypeBo.getWaterName(), waterTypeBo.getId());
        checkDefaultNotDisabled(waterTypeBo);
        if (isDefault(waterTypeBo.getDefaultFlag())) {
            clearOtherDefault(waterTypeBo.getId());
        }
        WsWaterType waterType = BeanUtil.copyProperties(waterTypeBo, WsWaterType.class);
        updateById(waterType);
        // 名称变更时同步出水口冗余名称（引用即冗余展示，REQ-073：配送端/出水口可见水种）
        if (ObjectUtil.notEqual(exist.getWaterName(), waterTypeBo.getWaterName())) {
            outletMapper.update(null, Wrappers.lambdaUpdate(WsDeviceOutlet.class)
                    .eq(WsDeviceOutlet::getWaterTypeId, waterTypeBo.getId())
                    .set(WsDeviceOutlet::getWaterType, waterTypeBo.getWaterName()));
        }
        return Boolean.TRUE;
    }

    @Override
    public Boolean deleteData(Long id) {
        WsWaterType waterType = getById(id);
        OptionalUtils.nullToElseThrow(waterType, "水种不存在");
        if (ApiEnum.DisabledFlag.NORMAL.getValue() == waterType.getWaterStatus()) {
            throw new JbkException("请先禁用该水种再删除");
        }
        long refCount = outletMapper.selectCount(Wrappers.lambdaQuery(WsDeviceOutlet.class)
                .eq(WsDeviceOutlet::getWaterTypeId, id));
        if (refCount > 0) {
            throw new JbkException("该水种被 " + refCount + " 个出水口引用，不可删除");
        }
        return removeById(id);
    }

    private boolean isDefault(Integer defaultFlag) {
        return ObjectUtil.isNotNull(defaultFlag) && ApiEnum.Flag.YES.value() == defaultFlag;
    }

    /** 名称业务唯一 */
    private void checkNameUnique(String waterName, Long excludeId) {
        long cnt = count(Wrappers.lambdaQuery(WsWaterType.class)
                .eq(WsWaterType::getWaterName, waterName)
                .ne(ObjectUtil.isNotNull(excludeId), WsWaterType::getId, excludeId));
        OptionalUtils.gtZeroElseThrow(cnt, "水种名称已存在");
    }

    /** 默认水种必须处于启用状态，取水链路将其作为未显式选择水种时的默认值。 */
    private void checkDefaultNotDisabled(WsWaterTypeBo bo) {
        if (isDefault(bo.getDefaultFlag())
                && ApiEnum.DisabledFlag.NORMAL.getValue() != bo.getWaterStatus()) {
            throw new JbkException("默认水种不允许禁用，请先取消默认");
        }
    }

    /** 保证全局唯一默认水种：移除其他水种的默认标记。 */
    private void clearOtherDefault(Long excludeId) {
        update(Wrappers.lambdaUpdate(WsWaterType.class)
                .eq(WsWaterType::getDefaultFlag, ApiEnum.Flag.YES.value())
                .ne(ObjectUtil.isNotNull(excludeId), WsWaterType::getId, excludeId)
                .set(WsWaterType::getDefaultFlag, ApiEnum.Flag.NO.value()));
    }

    /** 派生：被出水口引用数（删除前置校验参考） */
    private void fillOutletRefCount(List<WsWaterTypeVo> voList) {
        if (CollUtil.isEmpty(voList)) {
            return;
        }
        List<Long> idList = voList.stream().map(WsWaterTypeVo::getId).collect(Collectors.toList());
        Map<Long, Long> refMap = outletMapper.selectList(Wrappers.lambdaQuery(WsDeviceOutlet.class)
                        .in(WsDeviceOutlet::getWaterTypeId, idList)).stream()
                .filter(o -> ObjectUtil.isNotNull(o.getWaterTypeId()))
                .collect(Collectors.groupingBy(WsDeviceOutlet::getWaterTypeId, Collectors.counting()));
        voList.forEach(vo -> vo.setOutletRefCount(refMap.getOrDefault(vo.getId(), 0L)));
    }
}
