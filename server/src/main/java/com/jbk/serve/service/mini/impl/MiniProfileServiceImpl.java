package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.serve.service.mini.IMiniAuthService;
import com.jbk.serve.service.mini.IMiniProfileService;
import com.jbk.serve.service.mini.profile.ProfileAvatarStore;
import com.jbk.tool.data.mini.bo.MiniProfileUpdateBo;
import com.jbk.tool.data.mini.vo.MiniAccountContextVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * 小程序自助资料实现。
 *
 * <p>头像键派生沿用配送媒体的内容寻址口径：AV + sha256(userId:contentSha256) 前30位大写。
 * 同人同内容幂等命中同一文件（重写等价）；换头像即换键，旧文件成为孤儿——
 * demo 阶段不做回收（量级为单用户数十 KB），正式运维期由清理任务按列引用比对回收。</p>
 *
 * @author dakang
 * @since 2026-08-02
 */
@Service
@RequiredArgsConstructor
public class MiniProfileServiceImpl implements IMiniProfileService {

    /** 与配送媒体同白名单；扩展名用于落盘文件名与出图 Content-Type 双向映射。 */
    private static final Map<String, String> MIME_TO_EXT = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    /** 头像大小上限（解码后字节）。chooseAvatar 产物通常数十 KB，2MB 已含足量余量。 */
    private static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;

    private final WsUserIdentityMapper identityMapper;
    private final ProfileAvatarStore avatarStore;
    private final IMiniAuthService miniAuthService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MiniAccountContextVo updateProfile(Long userId, MiniProfileUpdateBo bo) {
        String nickname = StrUtil.trimToNull(bo.getUserName());
        boolean hasAvatar = StrUtil.isNotBlank(bo.getAvatarBase64());
        if (nickname == null && !hasAvatar) {
            throw new JbkException("昵称与头像至少提交其一");
        }
        String now = DateUtils.time();

        if (nickname != null) {
            // trim 后仍受 @Size(50) 之外的实质校验：空串在 trimToNull 已拦；控制字符会破坏各端展示
            if (nickname.chars().anyMatch(Character::isISOControl)) {
                throw new JbkException("昵称含有非法字符");
            }
            casOrFail(identityMapper.updateNicknameOfUsableUser(userId, nickname, userId, now));
        }

        if (hasAvatar) {
            String fileName = storeAvatar(userId, bo);
            casOrFail(identityMapper.updateAvatarOfUsableUser(
                    userId, "/mini/profile/avatar/" + fileName, userId, now));
        }

        return miniAuthService.currentContext(userId);
    }

    @Override
    public byte[] loadAvatar(String fileName) {
        return avatarStore.load(fileName);
    }

    /** 校验并落盘头像，返回受控文件名（AV键.扩展名）。 */
    private String storeAvatar(Long userId, MiniProfileUpdateBo bo) {
        String mime = StrUtil.trimToEmpty(bo.getAvatarMimeType()).toLowerCase(Locale.ROOT);
        String ext = MIME_TO_EXT.get(mime);
        // 入口白名单先于解码：类型对不上就不必花费 MB 级的 base64 解码（同配送媒体口径）
        if (ext == null) {
            throw new JbkException("头像仅支持 JPEG/PNG/WEBP");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(StrUtil.trimToEmpty(bo.getAvatarBase64()));
        } catch (IllegalArgumentException e) {
            throw new JbkException("头像内容不是合法的 base64");
        }
        if (content.length == 0 || content.length > MAX_AVATAR_BYTES) {
            throw new JbkException("头像为空或超过 2MB");
        }
        String contentSha = SecureUtil.sha256(new ByteArrayInputStream(content));
        String key = "AV" + SecureUtil.sha256(userId + ":" + contentSha)
                .substring(0, 30).toUpperCase(Locale.ROOT);
        String fileName = key + "." + ext;
        // 落盘失败抛出 → 整个事务回滚，不允许「列已更新而文件缺失」
        avatarStore.store(fileName, content);
        return fileName;
    }

    /** CAS 影响行数必须为 1；0 行=账号已删除/禁用/注销，fail-closed。 */
    private static void casOrFail(int affected) {
        if (affected != 1) {
            throw new JbkException("账号状态异常，无法更新资料，请联系客服");
        }
    }
}
