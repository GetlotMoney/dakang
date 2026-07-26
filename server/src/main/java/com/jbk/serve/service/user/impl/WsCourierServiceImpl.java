package com.jbk.serve.service.user.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.service.user.IWsUserService;
import com.jbk.serve.service.station.IWsStationService;
import com.jbk.serve.service.user.IWsCourierService;
import com.jbk.tool.consts.user.UserEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.user.bo.WsCourierBo;
import com.jbk.tool.data.user.po.WsCourier;
import com.jbk.tool.data.user.vo.WsCourierVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.OptionalUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 配送员服务实现（准入状态机，REQ-079）
 * <p>
 * 状态机：1待审核→2启用(通过)/4驳回；2启用→3停用；3停用→2启用；4驳回→2启用(复审通过)。
 * 驳回/停用必须填写备注；审核动作经 @LogOperation 落操作日志。
 * </p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Service
public class WsCourierServiceImpl extends ServiceImpl<WsCourierMapper, WsCourier> implements IWsCourierService {

    @Autowired
    private IWsUserService wsUserService;
    @Autowired
    private IWsStationService stationService;

    @Override
    public PageDataVo<WsCourierVo> pageData(WsCourierBo courierBo) {
        Page<WsCourier> page = page(new Page<>(courierBo.getCurrent(), courierBo.getSize()),
                Wrappers.lambdaQuery(WsCourier.class)
                        .like(StrUtil.isNotBlank(courierBo.getCourierName()), WsCourier::getCourierName,
                                courierBo.getCourierName())
                        .like(StrUtil.isNotBlank(courierBo.getCourierPhone()), WsCourier::getCourierPhone,
                                courierBo.getCourierPhone())
                        .eq(ObjectUtil.isNotNull(courierBo.getCourierStatus()), WsCourier::getCourierStatus,
                                courierBo.getCourierStatus())
                        .orderByAsc(WsCourier::getCourierStatus)
                        .orderByDesc(WsCourier::getId));
        List<WsCourierVo> voList = page.getRecords().stream()
                .map(e -> BeanUtil.copyProperties(e, WsCourierVo.class))
                .collect(Collectors.toList());
        fillDerived(voList);
        return PageDataVo.getPageData(voList, page.getTotal());
    }

    @Override
    public WsCourierVo getData(Long id) {
        WsCourier courier = getById(id);
        OptionalUtils.nullToElseThrow(courier, "配送员不存在");
        WsCourierVo vo = BeanUtil.copyProperties(courier, WsCourierVo.class);
        fillDerived(CollUtil.newArrayList(vo));
        return vo;
    }

    @Override
    public WsCourierVo getByUser(Long userId) {
        OptionalUtils.nullToElseThrow(userId, "用户信息不为空");
        WsCourier courier = getOne(Wrappers.lambdaQuery(WsCourier.class)
                .eq(WsCourier::getUserId, userId)
                .orderByDesc(WsCourier::getId)
                .last("LIMIT 1"));
        if (ObjectUtil.isNull(courier)) {
            return null;
        }
        WsCourierVo vo = BeanUtil.copyProperties(courier, WsCourierVo.class);
        fillDerived(CollUtil.newArrayList(vo));
        return vo;
    }

    @Override
    public Long saveData(WsCourierBo courierBo) {
        WsUser user = wsUserService.getById(courierBo.getUserId());
        OptionalUtils.nullToElseThrow(user, "关联用户不存在");
        // 同一用户只保留一条配送员准入记录；任意状态下均禁止重复创建。
        long cnt = count(Wrappers.lambdaQuery(WsCourier.class)
                .eq(WsCourier::getUserId, courierBo.getUserId()));
        OptionalUtils.gtZeroElseThrow(cnt, "该用户已存在配送员记录，请直接审核或启停");
        checkStationIds(courierBo.getStationIds());
        WsCourier courier = BeanUtil.copyProperties(courierBo, WsCourier.class);
        // 后台人工创建统一进待审核，审核通过才可接单（REQ-079 未过审不能进配送任务）
        courier.setCourierStatus(UserEnum.CourierStatus.PENDING.getValue());
        save(courier);
        return courier.getId();
    }

    @Override
    public Boolean audit(WsCourierBo courierBo) {
        WsCourier courier = getById(courierBo.getId());
        OptionalUtils.nullToElseThrow(courier, "配送员不存在");
        Integer target = courierBo.getTargetStatus();
        OptionalUtils.nullToElseThrow(target, "目标状态不为空");
        UserEnum.CourierStatus targetStatus = UserEnum.CourierStatus.getType(target);
        UserEnum.CourierStatus current = UserEnum.CourierStatus.getType(courier.getCourierStatus());
        checkTransition(current, targetStatus);
        boolean remarkRequired = targetStatus == UserEnum.CourierStatus.REJECTED
                || targetStatus == UserEnum.CourierStatus.DISABLED;
        if (remarkRequired && StrUtil.isBlank(courierBo.getAuditRemark())) {
            throw new JbkException(targetStatus.getDesc() + "操作必须填写备注");
        }
        // 更新条件包含原状态以实现乐观并发控制；影响行数为 0 表示审核状态已发生变化。
        boolean updated = update(Wrappers.lambdaUpdate(WsCourier.class)
                .eq(WsCourier::getId, courier.getId())
                .eq(WsCourier::getCourierStatus, current.getValue())
                .set(WsCourier::getCourierStatus, targetStatus.getValue())
                .set(StrUtil.isNotBlank(courierBo.getAuditRemark()), WsCourier::getAuditRemark,
                        courierBo.getAuditRemark()));
        if (!updated) {
            throw new JbkException("配送员状态已被其他操作变更，请刷新后重试");
        }
        return Boolean.TRUE;
    }

    /** 准入状态机合法流转校验 */
    private void checkTransition(UserEnum.CourierStatus current, UserEnum.CourierStatus target) {
        boolean ok = switch (target) {
            // 启用：待审核通过 / 停用恢复 / 驳回后复审通过
            case ENABLED -> current == UserEnum.CourierStatus.PENDING
                    || current == UserEnum.CourierStatus.DISABLED
                    || current == UserEnum.CourierStatus.REJECTED;
            // 驳回：仅待审核可驳回
            case REJECTED -> current == UserEnum.CourierStatus.PENDING;
            // 停用：仅启用中可停用
            case DISABLED -> current == UserEnum.CourierStatus.ENABLED;
            default -> false;
        };
        if (!ok) {
            throw new JbkException("不允许从「" + current.getDesc() + "」变更为「" + target.getDesc() + "」");
        }
    }

    /** 服务水站ID集合法性校验（存在性，防手输错ID导致任务范围失效） */
    private void checkStationIds(String stationIds) {
        if (StrUtil.isBlank(stationIds)) {
            return;
        }
        List<Long> idList;
        try {
            idList = Arrays.stream(stationIds.split(","))
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .map(Long::valueOf)
                    .distinct()
                    .collect(Collectors.toList());
        } catch (NumberFormatException e) {
            throw new JbkException("服务水站ID格式错误，应为逗号分隔的数字");
        }
        if (CollUtil.isEmpty(idList)) {
            return;
        }
        long existCnt = stationService.count(Wrappers.lambdaQuery(WsStation.class).in(WsStation::getId, idList));
        if (existCnt != idList.size()) {
            throw new JbkException("服务水站ID存在无效值，请核对水站列表");
        }
    }

    /** 填充派生字段：关联用户姓名、服务水站名称集 */
    private void fillDerived(List<WsCourierVo> voList) {
        if (CollUtil.isEmpty(voList)) {
            return;
        }
        List<Long> userIdList = voList.stream()
                .map(WsCourierVo::getUserId)
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
                }
            });
        }
        // 服务水站名称（汇总所有配送员涉及的站ID一次查询，避免 N+1）
        List<Long> stationIdList = voList.stream()
                .map(WsCourierVo::getStationIds)
                .filter(StrUtil::isNotBlank)
                .flatMap(ids -> Arrays.stream(ids.split(",")))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .map(Long::valueOf)
                .distinct()
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(stationIdList)) {
            return;
        }
        Map<Long, String> stationNameMap = stationService.listByIds(stationIdList).stream()
                .collect(Collectors.toMap(WsStation::getId, WsStation::getStationName));
        voList.forEach(vo -> {
            if (StrUtil.isBlank(vo.getStationIds())) {
                return;
            }
            String names = Arrays.stream(vo.getStationIds().split(","))
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .map(id -> stationNameMap.getOrDefault(Long.valueOf(id), "未知站#" + id))
                    .collect(Collectors.joining("、"));
            vo.setStationNames(names);
        });
    }
}
