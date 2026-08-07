package com.jbk.serve.service.delivery.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.delivery.WsDeliveryMediaMapper;
import com.jbk.serve.service.delivery.DeliveryMediaStore;
import com.jbk.serve.service.delivery.IDeliveryMediaService;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.data.delivery.po.WsDeliveryMedia;
import com.jbk.tool.exception.JbkException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 配送受控媒体服务实现（E2E-03 A5）。
 *
 * <p>键派生内容寻址：DM + sha256(ownerUserId:contentSha256:purpose) 前30位大写。
 * 同人同内容同用途重复登记幂等命中唯一键返回同键；claim 用条件 UPDATE 原子占用，
 * 归属/用途/复用三重校验都压在 WHERE 里，并发提交由数据库裁决。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Service
public class DeliveryMediaServiceImpl implements IDeliveryMediaService {

    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    @Autowired
    private WsDeliveryMediaMapper mediaMapper;
    @Autowired
    private DeliveryMediaStore mediaStore;

    /** 用户门户（1364）：旧接口的隐含登记身份域，与 OWNER_PORTAL 列默认值一致 */
    private static final int PORTAL_USER = 2;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String register(Long ownerUserId, DeliveryEnum.MediaPurpose purpose,
                           byte[] content, String mimeType, String now) {
        return registerAs(PORTAL_USER, ownerUserId, purpose, content, mimeType, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String registerAs(int ownerPortal, Long ownerUserId, DeliveryEnum.MediaPurpose purpose,
                             byte[] content, String mimeType, String now) {
        if (ownerUserId == null || ownerUserId <= 0 || purpose == null) {
            throw new JbkException("媒体登记参数非法");
        }
        if (content == null || content.length == 0 || content.length > MAX_SIZE_BYTES) {
            throw new JbkException("媒体内容为空或超出大小限制");
        }
        if (mimeType == null || !mimeType.startsWith("image/")) {
            throw new JbkException("仅支持图片类型媒体");
        }
        String contentSha = SecureUtil.sha256(new java.io.ByteArrayInputStream(content));
        String mediaKey = deriveKey(ownerPortal, ownerUserId, contentSha, purpose);
        WsDeliveryMedia media = new WsDeliveryMedia()
                .setMediaKey(mediaKey)
                .setOwnerUserId(ownerUserId)
                .setOwnerPortal(ownerPortal)
                .setMediaPurpose(purpose.getValue())
                .setContentSha256(contentSha)
                .setSizeBytes((long) content.length)
                .setMimeType(mimeType);
        media.setCreateTime(now);
        media.setUpdateTime(now);
        try {
            mediaMapper.insert(media);
        } catch (DuplicateKeyException e) {
            // 幂等：同键已登记。校验既有行与本次派生要素一致，防哈希键被异构行占用
            WsDeliveryMedia existing = mediaMapper.selectOne(Wrappers.lambdaQuery(WsDeliveryMedia.class)
                    .eq(WsDeliveryMedia::getMediaKey, mediaKey));
            if (ObjectUtil.isNull(existing)
                    || ObjectUtil.notEqual(existing.getOwnerUserId(), ownerUserId)
                    || ObjectUtil.notEqual(existing.getOwnerPortal(), ownerPortal)
                    || ObjectUtil.notEqual(existing.getMediaPurpose(), purpose.getValue())
                    || ObjectUtil.notEqual(existing.getContentSha256(), contentSha)) {
                throw new JbkException("媒体键冲突，请重试");
            }
            return mediaKey;
        }
        // 行先成立、文件后落盘：落盘失败抛出让整笔登记回滚，不允许「有行无文件」
        mediaStore.store(mediaKey, content);
        return mediaKey;
    }

    @Override
    public void claimForTask(List<String> mediaKeys, Long taskId, Long ownerUserId,
                             DeliveryEnum.MediaPurpose purpose, String failMessage) {
        claimForTaskAs(PORTAL_USER, mediaKeys, taskId, ownerUserId, purpose, failMessage);
    }

    @Override
    public void claimForTaskAs(int ownerPortal, List<String> mediaKeys, Long taskId, Long ownerUserId,
                               DeliveryEnum.MediaPurpose purpose, String failMessage) {
        if (mediaKeys == null || mediaKeys.isEmpty()) {
            return;
        }
        Set<String> distinct = new HashSet<>();
        for (String key : mediaKeys) {
            if (key == null || key.isBlank() || !distinct.add(key)) {
                // 空键或重复键：同一张照片顶两个位置就是造假，直接拒
                throw new JbkException(failMessage);
            }
            int affected = mediaMapper.update(null, Wrappers.lambdaUpdate(WsDeliveryMedia.class)
                    .set(WsDeliveryMedia::getBoundTaskId, taskId)
                    .eq(WsDeliveryMedia::getMediaKey, key)
                    .eq(WsDeliveryMedia::getOwnerUserId, ownerUserId)
                    .eq(WsDeliveryMedia::getOwnerPortal, ownerPortal)
                    .eq(WsDeliveryMedia::getMediaPurpose, purpose.getValue())
                    .isNull(WsDeliveryMedia::getBoundTaskId));
            if (affected != 1) {
                // 未登记 / 非本人 / 用途不符 / 已被占用：统一口径拒绝，不泄露他人媒体存在性
                throw new JbkException(failMessage);
            }
        }
    }

    /**
     * 键派生：旧用途（1-3）保持 owner:sha:purpose 三元（E2E-03 既有证据键不可漂移）；
     * 工单证据（4起）掺入门户段 portal:owner:sha:purpose——员工1号与用户1号登记同一张图
     * 必须得到两个不同的键，否则先登记者会占住后登记者的行。
     */
    static String deriveKey(int ownerPortal, Long ownerUserId, String contentSha256,
                            DeliveryEnum.MediaPurpose purpose) {
        String seed = purpose.getValue() >= DeliveryEnum.MediaPurpose.WORK_ORDER.getValue()
                ? ownerPortal + ":" + ownerUserId + ":" + contentSha256 + ":" + purpose.getValue()
                : ownerUserId + ":" + contentSha256 + ":" + purpose.getValue();
        String digest = SecureUtil.sha256(seed);
        return "DM" + digest.substring(0, 30).toUpperCase();
    }
}
