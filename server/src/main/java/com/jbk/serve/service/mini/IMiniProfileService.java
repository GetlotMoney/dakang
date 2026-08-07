package com.jbk.serve.service.mini;

import com.jbk.tool.data.mini.bo.MiniProfileUpdateBo;
import com.jbk.tool.data.mini.vo.MiniAccountContextVo;

/**
 * 小程序自助资料（昵称/头像）。
 * <p>作用范围恒为当前会话用户（铁律6：不收前端 userId）；全程 fail-closed。
 * 头像走「资料填写能力」动线（微信 2022 年底废除授权弹窗后的唯一合规路径），
 * 服务端只接 base64 内容并派生内容寻址键，不信任任何客户端文件名。</p>
 *
 * @author dakang
 * @since 2026-08-02
 */
public interface IMiniProfileService {

    /**
     * 更新当前用户资料（昵称/头像至少其一），返回刷新后的账号上下文（不换发会话）。
     */
    MiniAccountContextVo updateProfile(Long userId, MiniProfileUpdateBo bo);

    /**
     * 按受控文件名读取头像字节；不存在返回 null。
     * <p>供 GET 出图端点使用：小程序 `image` 标签无法携带会话头，
     * 访问控制靠不可猜测的内容寻址文件名（AV+30位十六进制）。</p>
     */
    byte[] loadAvatar(String fileName);
}
