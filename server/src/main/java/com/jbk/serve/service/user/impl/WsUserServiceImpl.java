package com.jbk.serve.service.user.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.serve.service.user.IWsUserService;
import com.jbk.tool.consts.user.UserEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.user.bo.WsUserBo;
import com.jbk.tool.data.user.po.WsCourier;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.data.user.vo.WsUserVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.OptionalUtils;
import com.jbk.tool.utils.PhoneMask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * C 端用户服务。
 */
@Service
public class WsUserServiceImpl extends ServiceImpl<WsUserMapper, WsUser> implements IWsUserService {

    /** 机主能力：名下有水站或设备归属行。取值与 MiniCapabilityServiceImpl 保持逐字一致。 */
    public static final String CAPABILITY_OWNER = "OWNER_VIEW";
    /** 配送能力：存在启用态配送员准入记录。待审核/停用/驳回都不算「有能力」。 */
    public static final String CAPABILITY_COURIER = "COURIER_WORK";

    @Autowired
    private WsDeviceMapper deviceMapper;
    @Autowired
    private WsStationMapper stationMapper;
    @Autowired
    private WsCourierMapper courierMapper;

    // 获取用户
    @Override
    public WsUserVo getData(Long id) {
        WsUser wsUser = getById(id);
        OptionalUtils.nullToElseThrow(wsUser, "用户信息不存在");
        WsUserVo vo = BeanUtil.copyProperties(wsUser, WsUserVo.class);
        decorate(CollUtil.newArrayList(vo));
        return vo;
    }

    // 【2026-08-12 删除 updateData】它没有任何控制器调用方，但写法是
    // BeanUtil.copyProperties(Bo → PO) + updateById：Bo 上有什么同名字段就整片写进去。
    // 当时 WsUserBo 带着 wechatXcxOpenid，于是这是一条完整的「后台改任意用户微信身份」通路，
    // 只差一个 @PostMapping。而它长得就像一个忘了补全的 CRUD，后续迭代顺手接上毫无违和感。
    // 走这条路改 openid 不撞唯一键（先清空 A 再写 B 即可），登录链所有 fail-closed 判定
    // 都在这条路径之外——A 的微信从此打开 B 的账号，没有任何日志会说这件事发生过。
    //
    // 账号身份的唯一写入口是 MiniAuthBindTxImpl（CAS + 唯一键 + 影响行数判定）。
    // 将来 PC 需要「停用/注销账号」时，请新写一个只改状态列的窄方法，
    // 不要复活整片 copyProperties——护栏见 UserIdentityWritePathTest。
    @Override
    public PageDataVo<WsUserVo> pageData(WsUserBo wsUserBo) {
        requireStorageTimeFormat(wsUserBo.getCreateTimeBegin(), "注册开始时间");
        requireStorageTimeFormat(wsUserBo.getCreateTimeEnd(), "注册结束时间");
        LambdaQueryWrapper<WsUser> wrapper = Wrappers.lambdaQuery(WsUser.class)
                // 用户 ID 走等值：运营拿到一个确切编号时，模糊关键词会串到同名或近号的另一个人。
                .eq(ObjectUtil.isNotNull(wsUserBo.getId()), WsUser::getId, wsUserBo.getId())
                .eq(StrUtil.isNotEmpty(wsUserBo.getUserPhone()), WsUser::getUserPhone, wsUserBo.getUserPhone())
                .like(StrUtil.isNotEmpty(wsUserBo.getUserName()), WsUser::getUserName, wsUserBo.getUserName())
                .eq(ObjectUtil.isNotNull(wsUserBo.getDisabledFlag()), WsUser::getDisabledFlag, wsUserBo.getDisabledFlag())
                // CREATE_TIME 为 varchar(14)，与 Bo 上的 14 位正则配对；格式已在入参处拒绝，此处直接比较
                .ge(StrUtil.isNotEmpty(wsUserBo.getCreateTimeBegin()), WsUser::getCreateTime, wsUserBo.getCreateTimeBegin())
                .le(StrUtil.isNotEmpty(wsUserBo.getCreateTimeEnd()), WsUser::getCreateTime, wsUserBo.getCreateTimeEnd())
                .orderByDesc(WsUser::getId);
        applyCapabilityFilter(wrapper, wsUserBo.getCapability());
        Page<WsUser> page = page(new Page<>(wsUserBo.getCurrent(), wsUserBo.getSize()), wrapper);
        List<WsUserVo> voList = page.getRecords().stream()
                .map(e -> BeanUtil.copyProperties(e, WsUserVo.class))
                .collect(Collectors.toList());
        decorate(voList);
        return PageDataVo.getPageData(voList, page.getTotal());
    }

    /**
     * 时间区间格式复核。CREATE_TIME 存的是 varchar(14)，比较按字符逐位进行：
     * 传 '2026-08-13' 时 '-'(0x2D) 小于所有数字，上界会把当年记录整批排除且不报任何错——
     * 界面上看是「这个区间没有人注册」，而不是「条件写错了」。格式不符一律拒绝，不做任何猜测性补全。
     */
    private void requireStorageTimeFormat(String value, String label) {
        if (StrUtil.isEmpty(value)) {
            return;
        }
        if (!value.matches(WsUserBo.TIME_14)) {
            throw new JbkException(label + "格式不正确");
        }
    }

    /**
     * 能力筛选。子查询里的 DATA_STATUS 必须显式写出——inSql 是裸 SQL，
     * @TableLogic 不会介入，漏写会把已删除的归属行也算成「有能力」。
     * 未知取值 fail-closed 抛错，绝不退化成不过滤。
     */
    private void applyCapabilityFilter(LambdaQueryWrapper<WsUser> wrapper, String capability) {
        if (StrUtil.isBlank(capability)) {
            return;
        }
        if (CAPABILITY_OWNER.equals(capability)) {
            wrapper.inSql(WsUser::getId,
                    "SELECT OWNER_USER_ID FROM ws_device WHERE DATA_STATUS = 0 AND OWNER_USER_ID IS NOT NULL"
                            + " UNION SELECT OWNER_USER_ID FROM ws_station WHERE DATA_STATUS = 0 AND OWNER_USER_ID IS NOT NULL");
            return;
        }
        if (CAPABILITY_COURIER.equals(capability)) {
            wrapper.inSql(WsUser::getId,
                    "SELECT USER_ID FROM ws_courier WHERE DATA_STATUS = 0 AND COURIER_STATUS = "
                            + UserEnum.CourierStatus.ENABLED.getValue());
            return;
        }
        throw new JbkException("能力条件不正确");
    }

    /**
     * 列表/详情共用的出口加工：手机号脱敏 + 能力标签投影。
     * <p>两条读路径都必须经过本方法：只要有一条绕开，明文号码就会从那条路径流出去。
     * 能力标签按整页一次性批量取（三条 IN 查询），不逐行查库。</p>
     */
    private void decorate(List<WsUserVo> voList) {
        if (CollUtil.isEmpty(voList)) {
            return;
        }
        voList.forEach(vo -> vo.setUserPhone(PhoneMask.mask(vo.getUserPhone())));
        List<Long> userIdList = voList.stream()
                .map(WsUserVo::getId)
                .filter(ObjectUtil::isNotNull)
                .distinct()
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(userIdList)) {
            return;
        }
        Set<Long> owners = new HashSet<>();
        deviceMapper.selectList(Wrappers.lambdaQuery(WsDevice.class)
                        .select(WsDevice::getOwnerUserId)
                        .in(WsDevice::getOwnerUserId, userIdList))
                .forEach(device -> owners.add(device.getOwnerUserId()));
        stationMapper.selectList(Wrappers.lambdaQuery(WsStation.class)
                        .select(WsStation::getOwnerUserId)
                        .in(WsStation::getOwnerUserId, userIdList))
                .forEach(station -> owners.add(station.getOwnerUserId()));
        Set<Long> couriers = courierMapper.selectList(Wrappers.lambdaQuery(WsCourier.class)
                        .select(WsCourier::getUserId)
                        .in(WsCourier::getUserId, userIdList)
                        .eq(WsCourier::getCourierStatus, UserEnum.CourierStatus.ENABLED.getValue()))
                .stream()
                .map(WsCourier::getUserId)
                .collect(Collectors.toSet());
        voList.forEach(vo -> {
            List<String> capabilities = new ArrayList<>(2);
            if (owners.contains(vo.getId())) {
                capabilities.add(CAPABILITY_OWNER);
            }
            if (couriers.contains(vo.getId())) {
                capabilities.add(CAPABILITY_COURIER);
            }
            vo.setCapabilities(capabilities);
        });
    }

}
