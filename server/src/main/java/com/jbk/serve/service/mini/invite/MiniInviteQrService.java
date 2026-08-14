package com.jbk.serve.service.mini.invite;

import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.settlement.IInviteService;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 本人邀请小程序码（WX-ECO S2）。归属人只从会话取，不接受 userId/inviteCode 入参——
 * 前端可指定即可生成"归属于别人"的码，而归属一次性不可逆（D-413，铁律 6）。
 * 本轮只出 scene 不出图（任务书禁真实外呼），接真时补取图适配器、scene 逻辑不改。
 * 每次出码落领域事件：出码是归属链的源头。
 *
 * @author dakang
 * @since 2026-08-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MiniInviteQrService {

    private final IInviteService inviteService;
    private final InviteSceneCodec sceneCodec;
    private final IWsDomainEventService domainEventService;

    /**
     * 生成当前会话用户的邀请码 scene。
     *
     * @param userId 由控制器从会话取，绝不来自请求体
     * @return 带签名与到期日的 scene，供小程序码生成接口使用
     */
    public String issueScene(Long userId) {
        if (userId == null) {
            throw new JbkException("会话身份缺失");
        }
        // 复用惰性生成的唯一邀请码（D-413：只有一个码），不另造推广码
        String inviteCode = inviteService.myInviteCode(userId);
        String scene;
        try {
            scene = sceneCodec.encode(inviteCode);
        }
        catch (IllegalStateException e) {
            // 密钥未配 / scene 超长：都是配置或数据问题，明确拒绝而不是出一张扫了没用的码
            log.warn("邀请码 scene 生成失败 userId={}", userId, e);
            throw new JbkException("邀请码暂不可用，请稍后再试");
        }
        // 出码留痕；幂等键带 userId：同一人反复出码只留一条
        domainEventService.recordReliableOnceAs(OpsEnum.ActorPortal.USER, userId,
                OpsEnum.EventType.ORDER_STATUS, String.valueOf(userId),
                "INVITE_QR:" + userId, null,
                // 只记"谁在什么时候出过码"，不记 scene 本身：scene 带签名，
                // 落进审计流水等于把一份可直接使用的归属凭据抄进了另一张表
                "生成邀请小程序码 scene");
        return scene;
    }
}
