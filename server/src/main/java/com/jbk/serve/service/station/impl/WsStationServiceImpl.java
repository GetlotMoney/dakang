package com.jbk.serve.service.station.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.service.user.IWsUserService;
import com.jbk.serve.service.station.IWsStationService;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.data.station.bo.WsStationBo;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.station.vo.WsStationVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.OptionalUtils;
import com.jbk.tool.utils.PhoneMask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 水站服务实现
 *
 * @author dakang
 * @since 2026-07-12
 */
@Service
public class WsStationServiceImpl extends ServiceImpl<WsStationMapper, WsStation> implements IWsStationService {

    @Autowired
    private IWsUserService wsUserService;

    @Override
    public PageDataVo<WsStationVo> pageData(WsStationBo stationBo) {
        Page<WsStation> page = page(new Page<>(stationBo.getCurrent(), stationBo.getSize()),
                Wrappers.lambdaQuery(WsStation.class)
                        .like(StrUtil.isNotBlank(stationBo.getStationName()), WsStation::getStationName, stationBo.getStationName())
                        .like(StrUtil.isNotBlank(stationBo.getStationCode()), WsStation::getStationCode, stationBo.getStationCode())
                        .like(StrUtil.isNotBlank(stationBo.getStationRegion()), WsStation::getStationRegion, stationBo.getStationRegion())
                        .eq(ObjectUtil.isNotNull(stationBo.getStationStatus()), WsStation::getStationStatus, stationBo.getStationStatus())
                        .orderByDesc(WsStation::getId)
        );
        List<WsStationVo> voList = page.getRecords().stream()
                .map(e -> BeanUtil.copyProperties(e, WsStationVo.class))
                .collect(Collectors.toList());
        fillDerived(voList);
        return PageDataVo.getPageData(voList, page.getTotal());
    }

    @Override
    public WsStationVo getData(Long id) {
        WsStation station = getById(id);
        OptionalUtils.nullToElseThrow(station, "水站不存在");
        WsStationVo vo = BeanUtil.copyProperties(station, WsStationVo.class);
        fillDerived(CollUtil.newArrayList(vo));
        return vo;
    }

    @Override
    public Long saveData(WsStationBo stationBo) {
        // 编码业务唯一，代码层查重
        long cntCode = count(Wrappers.lambdaQuery(WsStation.class)
                .eq(WsStation::getStationCode, stationBo.getStationCode()));
        OptionalUtils.gtZeroElseThrow(cntCode, "水站编码已存在");
        checkOwnerUser(stationBo.getOwnerUserId());
        WsStation station = BeanUtil.copyProperties(stationBo, WsStation.class);
        save(station);
        return station.getId();
    }

    @Override
    public Boolean updateData(WsStationBo stationBo) {
        WsStation exist = getById(stationBo.getId());
        OptionalUtils.nullToElseThrow(exist, "水站不存在");
        long cntCode = count(Wrappers.lambdaQuery(WsStation.class)
                .ne(WsStation::getId, stationBo.getId())
                .eq(WsStation::getStationCode, stationBo.getStationCode()));
        OptionalUtils.gtZeroElseThrow(cntCode, "水站编码已存在");
        checkOwnerUser(stationBo.getOwnerUserId());
        WsStation station = BeanUtil.copyProperties(stationBo, WsStation.class);
        // 机主可解绑：MP 默认策略对 null 字段跳过更新，必须显式 set null 才能真正解绑
        if (ObjectUtil.isNull(stationBo.getOwnerUserId())) {
            update(Wrappers.lambdaUpdate(WsStation.class)
                    .eq(WsStation::getId, stationBo.getId())
                    .set(WsStation::getOwnerUserId, null));
        }
        updateById(station);
        return Boolean.TRUE;
    }

    @Override
    public Boolean deleteData(Long id) {
        WsStation station = getById(id);
        OptionalUtils.nullToElseThrow(station, "水站不存在");
        // 站下有设备禁删，防止设备失去归属（订单/指令按站追溯的根）
        Map<Long, Long> countMap = deviceCountMap(CollUtil.newArrayList(id));
        long deviceCount = countMap.getOrDefault(id, 0L);
        if (deviceCount > 0L) {
            throw new JbkException("该水站下存在 " + deviceCount + " 台设备，请先迁移或删除设备");
        }
        return removeById(id);
    }

    @Override
    public List<WsStationVo> listData() {
        List<WsStation> list = list(Wrappers.lambdaQuery(WsStation.class)
                .eq(WsStation::getStationStatus, ApiEnum.DisabledFlag.NORMAL.getValue())
                .orderByDesc(WsStation::getId));
        return list.stream().map(e -> BeanUtil.copyProperties(e, WsStationVo.class)).collect(Collectors.toList());
    }

    /** 机主用户存在性校验（绑定了才校验） */
    private void checkOwnerUser(Long ownerUserId) {
        if (ObjectUtil.isNull(ownerUserId)) {
            return;
        }
        WsUser user = wsUserService.getById(ownerUserId);
        OptionalUtils.nullToElseThrow(user, "机主用户不存在");
    }

    /**
     * 填充派生字段：机主姓名/手机号、设备数。
     * <p>机主是 C 端 ws_user，手机号一律经 {@link PhoneMask} 脱敏后下发：水站列表/详情是任何持后台
     * 会话的账号都能翻的面，此处若下发原值，用户域刚建立的号码脱敏口径就被这条旁路整条绕过
     * （同一个机主下拉里，远程搜索来的是脱敏值、编辑回显来的是明文，同屏两种形态）。
     * 机主手机号只作展示，写入只认 ownerUserId（{@code WsStationBo} 无号码字段），脱敏不影响保存。</p>
     */
    private void fillDerived(List<WsStationVo> voList) {
        if (CollUtil.isEmpty(voList)) {
            return;
        }
        // 机主信息
        List<Long> ownerIdList = voList.stream()
                .map(WsStationVo::getOwnerUserId)
                .filter(ObjectUtil::isNotNull)
                .distinct()
                .collect(Collectors.toList());
        if (CollUtil.isNotEmpty(ownerIdList)) {
            Map<Long, WsUser> userMap = wsUserService.listByIds(ownerIdList).stream()
                    .collect(Collectors.toMap(WsUser::getId, Function.identity()));
            voList.forEach(vo -> {
                WsUser user = userMap.get(vo.getOwnerUserId());
                if (ObjectUtil.isNotNull(user)) {
                    vo.setOwnerUserName(user.getUserName());
                    vo.setOwnerUserPhone(PhoneMask.mask(user.getUserPhone()));
                }
            });
        }
        // 设备数
        List<Long> stationIdList = voList.stream().map(WsStationVo::getId).collect(Collectors.toList());
        Map<Long, Long> countMap = deviceCountMap(stationIdList);
        voList.forEach(vo -> vo.setDeviceCount(countMap.getOrDefault(vo.getId(), 0L)));
    }

    private Map<Long, Long> deviceCountMap(List<Long> stationIdList) {
        if (CollUtil.isEmpty(stationIdList)) {
            return Map.of();
        }
        List<Map<String, Object>> rows = baseMapper.countDeviceByStation(stationIdList);
        return rows.stream().collect(Collectors.toMap(
                r -> Long.valueOf(String.valueOf(r.get("stationId"))),
                r -> Long.valueOf(String.valueOf(r.get("deviceCount")))
        ));
    }
}
