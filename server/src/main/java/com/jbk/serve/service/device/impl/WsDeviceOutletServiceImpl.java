package com.jbk.serve.service.device.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.device.WsQrcodeMapper;
import com.jbk.serve.service.device.IWsDeviceOutletService;
import com.jbk.serve.service.product.IWsWaterTypeService;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.data.device.bo.WsDeviceOutletBo;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.device.po.WsQrcode;
import com.jbk.tool.data.device.vo.WsDeviceOutletVo;
import com.jbk.tool.data.product.po.WsWaterType;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.OptionalUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 设备出水口服务实现
 * <p>水种校验：新增/换水种时必须为启用状态（REQ-073：禁用水种新出水口不可选，
 * 已绑定的出水口不受禁用影响继续可用）。WATER_TYPE 名称列为展示冗余，随水种ID回填。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Service
public class WsDeviceOutletServiceImpl extends ServiceImpl<WsDeviceOutletMapper, WsDeviceOutlet> implements IWsDeviceOutletService {

    @Autowired
    private IWsWaterTypeService waterTypeService;

    /** 直接用设备 Mapper 校验归属，避免出水口服务与设备服务互相注入成环 */
    @Autowired
    private WsDeviceMapper deviceMapper;
    @Autowired
    private WsQrcodeMapper qrcodeMapper;

    @Override
    public List<WsDeviceOutletVo> listByDevice(Long deviceId) {
        List<WsDeviceOutlet> list = list(Wrappers.lambdaQuery(WsDeviceOutlet.class)
                .eq(WsDeviceOutlet::getDeviceId, deviceId)
                .orderByAsc(WsDeviceOutlet::getOutletNo));
        return list.stream().map(e -> BeanUtil.copyProperties(e, WsDeviceOutletVo.class)).collect(Collectors.toList());
    }

    @Override
    public Long saveData(WsDeviceOutletBo outletBo) {
        OptionalUtils.nullToElseThrow(deviceMapper.selectById(outletBo.getDeviceId()), "所属设备不存在");
        checkOutletNoUnique(outletBo.getDeviceId(), outletBo.getOutletNo(), null);
        WsWaterType waterType = checkWaterType(outletBo.getWaterTypeId());
        WsDeviceOutlet outlet = BeanUtil.copyProperties(outletBo, WsDeviceOutlet.class);
        outlet.setWaterType(waterType.getWaterName());
        save(outlet);
        return outlet.getId();
    }

    @Override
    public Boolean updateData(WsDeviceOutletBo outletBo) {
        WsDeviceOutlet exist = getById(outletBo.getId());
        OptionalUtils.nullToElseThrow(exist, "出水口不存在");
        checkOutletNoUnique(exist.getDeviceId(), outletBo.getOutletNo(), outletBo.getId());
        WsDeviceOutlet outlet = BeanUtil.copyProperties(outletBo, WsDeviceOutlet.class);
        // 归属设备不允许换绑（出水口是设备的物理部件）；MP 默认策略 null 字段不更新
        outlet.setDeviceId(null);
        // 历史行 WATER_TYPE_ID 可能为空（补丁按名称回填未命中），用 notEqual 防 NPE
        if (ObjectUtil.notEqual(exist.getWaterTypeId(), outletBo.getWaterTypeId())) {
            WsWaterType waterType = checkWaterType(outletBo.getWaterTypeId());
            outlet.setWaterType(waterType.getWaterName());
        }
        updateById(outlet);
        return Boolean.TRUE;
    }

    @Override
    public Boolean deleteData(Long id) {
        WsDeviceOutlet outlet = getById(id);
        OptionalUtils.nullToElseThrow(outlet, "出水口不存在");
        // 被二维码绑定的出水口禁删，防止扫码解析悬空
        long qrCnt = qrcodeMapper.selectCount(Wrappers.lambdaQuery(WsQrcode.class)
                .eq(WsQrcode::getOutletId, id));
        if (qrCnt > 0) {
            throw new JbkException("该出水口被 " + qrCnt + " 个二维码绑定，请先解绑");
        }
        // TODO 订单模块接入后：存在关联取水订单的出水口禁删（追溯根），改为禁用
        return removeById(id);
    }

    /** 同设备内出水口编号唯一（物理口位不可重复） */
    private void checkOutletNoUnique(Long deviceId, Integer outletNo, Long excludeId) {
        long cnt = count(Wrappers.lambdaQuery(WsDeviceOutlet.class)
                .eq(WsDeviceOutlet::getDeviceId, deviceId)
                .eq(WsDeviceOutlet::getOutletNo, outletNo)
                .ne(excludeId != null, WsDeviceOutlet::getId, excludeId));
        if (cnt > 0) {
            throw new JbkException("该设备已存在出水口 " + outletNo + " 号");
        }
    }

    /** 水种必须存在且启用 */
    private WsWaterType checkWaterType(Long waterTypeId) {
        WsWaterType waterType = waterTypeService.getById(waterTypeId);
        OptionalUtils.nullToElseThrow(waterType, "水种不存在");
        if (waterType.getWaterStatus() != ApiEnum.DisabledFlag.NORMAL.getValue()) {
            throw new JbkException("水种已禁用，不可选择");
        }
        return waterType;
    }
}
