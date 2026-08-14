package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.user.WsFamilyProfileMapper;
import com.jbk.serve.mapper.user.WsUserAddressMapper;
import com.jbk.serve.service.mini.IMiniFamilyService;
import com.jbk.tool.data.mini.bo.MiniAddressSaveBo;
import com.jbk.tool.data.mini.bo.MiniFamilySaveBo;
import com.jbk.tool.data.mini.vo.MiniAddressVo;
import com.jbk.tool.data.mini.vo.MiniFamilyProfileVo;
import com.jbk.tool.data.user.po.WsFamilyProfile;
import com.jbk.tool.data.user.po.WsUserAddress;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.PhoneMask;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 家庭资料与配送地址实现。
 *
 * <p>要点：所有查询恒带会话人 USER_ID 条件（@TableLogic 负责逻辑删除过滤）；
 * 电话对外一律 {@link PhoneMask}（唯一实现，规则不许分叉）；默认地址在事务内先清后设；
 * 家庭资料一人一份，并发建档由 uk_family_user 兜底，隐私同意时间首存后不改写。</p>
 *
 * @author dakang
 * @since 2026-08-02
 */
@Service
@RequiredArgsConstructor
public class MiniFamilyServiceImpl implements IMiniFamilyService {

    /** 单人地址条数上限：够日常使用，防脚本刷表。 */
    private static final long MAX_ADDRESSES = 20;

    private final WsUserAddressMapper addressMapper;
    private final WsFamilyProfileMapper familyMapper;

    // ==================== 地址簿 ====================

    @Override
    public List<MiniAddressVo> listAddresses(Long userId) {
        return addressMapper.selectList(Wrappers.lambdaQuery(WsUserAddress.class)
                        .eq(WsUserAddress::getUserId, userId)
                        .orderByDesc(WsUserAddress::getIsDefault)
                        .orderByDesc(WsUserAddress::getId))
                .stream().map(MiniFamilyServiceImpl::toAddressVo).collect(Collectors.toList());
    }

    @Override
    public MiniAddressVo getAddress(Long userId, Long addressId) {
        return toAddressVo(requireOwnAddress(userId, addressId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MiniAddressVo saveAddress(Long userId, MiniAddressSaveBo bo) {
        String now = DateUtils.time();
        WsUserAddress target;
        if (ObjectUtil.isNotNull(bo.getAddressId())) {
            target = requireOwnAddress(userId, bo.getAddressId());
        }
        else {
            long count = addressMapper.selectCount(Wrappers.lambdaQuery(WsUserAddress.class)
                    .eq(WsUserAddress::getUserId, userId));
            if (count >= MAX_ADDRESSES) {
                throw new JbkException("地址数量已达上限（" + MAX_ADDRESSES + " 条），请先删除不用的地址");
            }
            target = new WsUserAddress().setUserId(userId).setIsDefault(0).setLocationAuthorized(0);
            target.setCreateBy(userId);
            target.setCreateTime(now);
        }
        target.setContactName(StrUtil.trim(bo.getContactName()))
                .setContactPhone(StrUtil.trim(bo.getPhone()))
                .setRegion(StrUtil.trim(bo.getRegion()))
                // 区县码留空即存 NULL：商城侧据此提示补选，绝不按 region 文本猜测（E2E-09 S2）
                .setDistrictCode(StrUtil.emptyToNull(StrUtil.trim(bo.getDistrictCode())))
                .setAddressDetail(StrUtil.trim(bo.getDetail()))
                .setLocationAuthorized(Boolean.TRUE.equals(bo.getLocationAuthorized()) ? 1 : 0);
        // 默认位互斥：设默认先清同人全部默认位；取消默认只落本行
        if (Boolean.TRUE.equals(bo.getIsDefault())) {
            addressMapper.clearDefault(userId, userId, now);
            target.setIsDefault(1);
        }
        else {
            target.setIsDefault(0);
        }
        target.setUpdateBy(userId);
        target.setUpdateTime(now);
        if (ObjectUtil.isNull(target.getId())) {
            addressMapper.insert(target);
        }
        else {
            addressMapper.updateById(target);
        }
        return toAddressVo(target);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAddress(Long userId, Long addressId) {
        WsUserAddress target = requireOwnAddress(userId, addressId);
        addressMapper.deleteById(target.getId());
    }

    @Override
    public WsUserAddress requireOwnAddress(Long userId, Long addressId) {
        WsUserAddress found = addressMapper.selectOne(Wrappers.lambdaQuery(WsUserAddress.class)
                .eq(WsUserAddress::getId, addressId)
                .eq(WsUserAddress::getUserId, userId));
        if (ObjectUtil.isNull(found)) {
            // 归属条件压在查询里：他人地址与不存在同响应，不泄露存在性
            throw new JbkException("地址不存在或已删除");
        }
        return found;
    }

    private static MiniAddressVo toAddressVo(WsUserAddress po) {
        return new MiniAddressVo()
                .setAddressId(String.valueOf(po.getId()))
                .setContactName(po.getContactName())
                .setMaskedPhone(PhoneMask.mask(po.getContactPhone()))
                .setRegion(po.getRegion())
                .setDistrictCode(po.getDistrictCode())
                .setDetail(po.getAddressDetail())
                .setIsDefault(ObjectUtil.equal(po.getIsDefault(), 1))
                .setLocationAuthorized(ObjectUtil.equal(po.getLocationAuthorized(), 1));
    }

    // ==================== 家庭资料 ====================

    @Override
    public MiniFamilyProfileVo getFamilyProfile(Long userId) {
        WsFamilyProfile found = familyMapper.selectOne(Wrappers.lambdaQuery(WsFamilyProfile.class)
                .eq(WsFamilyProfile::getUserId, userId));
        return ObjectUtil.isNull(found) ? null : toFamilyVo(found);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MiniFamilyProfileVo saveFamilyProfile(Long userId, MiniFamilySaveBo bo) {
        String now = DateUtils.time();
        WsFamilyProfile existing = familyMapper.selectOne(Wrappers.lambdaQuery(WsFamilyProfile.class)
                .eq(WsFamilyProfile::getUserId, userId));
        if (ObjectUtil.isNull(existing)) {
            // 首次建档必须显式同意隐私说明；同意时间记服务端时间，此后更新不改写
            if (!Boolean.TRUE.equals(bo.getPrivacyAccepted())) {
                throw new JbkException("请先阅读并同意隐私说明");
            }
            WsFamilyProfile created = new WsFamilyProfile()
                    .setUserId(userId)
                    .setPrivacyConsentTime(now)
                    .setMemberCount(bo.getMemberCount())
                    .setWaterHabitNote(StrUtil.trimToNull(bo.getWaterHabitNote()));
            created.setCreateBy(userId);
            created.setCreateTime(now);
            created.setUpdateBy(userId);
            created.setUpdateTime(now);
            try {
                familyMapper.insert(created);
            }
            catch (DuplicateKeyException e) {
                // 并发双开建档：uk_family_user 兜底，指引重试即可（幂等语义由重读承担）
                throw new JbkException("资料已存在，请刷新后重试");
            }
            return toFamilyVo(created);
        }
        existing.setMemberCount(bo.getMemberCount())
                .setWaterHabitNote(StrUtil.trimToNull(bo.getWaterHabitNote()));
        existing.setUpdateBy(userId);
        existing.setUpdateTime(now);
        familyMapper.updateById(existing);
        return toFamilyVo(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFamilyProfile(Long userId) {
        WsFamilyProfile existing = familyMapper.selectOne(Wrappers.lambdaQuery(WsFamilyProfile.class)
                .eq(WsFamilyProfile::getUserId, userId));
        if (ObjectUtil.isNotNull(existing)) {
            familyMapper.deleteById(existing.getId());
        }
    }

    private static MiniFamilyProfileVo toFamilyVo(WsFamilyProfile po) {
        return new MiniFamilyProfileVo()
                .setProfileId(String.valueOf(po.getId()))
                .setPrivacyConsentTime(po.getPrivacyConsentTime())
                .setMemberCount(po.getMemberCount())
                .setWaterHabitNote(po.getWaterHabitNote())
                .setUpdatedTime(po.getUpdateTime());
    }
}
