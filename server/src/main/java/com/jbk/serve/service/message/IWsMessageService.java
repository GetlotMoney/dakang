package com.jbk.serve.service.message;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.message.po.WsMessage;
import com.jbk.tool.data.mini.bo.MiniMessageBo;
import com.jbk.tool.data.mini.vo.MiniMessageVo;

/**
 * 站内消息服务（E2E-03 A6：一期只做站内渠道，不接微信订阅消息）。
 * <p>E2E-07 扩展：收件人读端（分页/详情/已读，铁律6 会话强制过滤）与
 * 发送状态机骨架（重试/降级，纯条件更新幂等，为渠道扩展预留，无调度器）。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
public interface IWsMessageService extends IService<WsMessage> {

    /**
     * 发送站内消息：与调用方业务动作同事务落库（同生共死），SEND_TIME 与动作时间同源。
     * 写入失败向上抛出，由业务事务整体回滚——消息不是尽力而为的旁路，而是履约证据链的一环。
     *
     * @param userId     收件用户
     * @param domain     消息领域
     * @param title      标题
     * @param content    正文（禁止含明文手机号等敏感信息）
     * @param objectType 关联对象类型（order/task/appeal/...）
     * @param objectId   关联对象业务键
     * @param sendTime   业务动作时间（yyyyMMddHHmmss）
     */
    void sendInApp(Long userId, MessageEnum.MsgDomain domain, String title, String content,
                   String objectType, String objectId, String sendTime);

    /**
     * 本人消息分页：按会话 userId 强制过滤（铁律6），可选领域筛选；
     * 排序=发送时间倒序、无发送时间排尾（对齐 miniapp mock 冻结口径）。
     */
    PageDataVo<MiniMessageVo> pageForUser(MiniMessageBo bo, Long userId);

    /** 本人消息详情：越权与不存在同文案（存在性不泄露）。 */
    MiniMessageVo detailForUser(Long messageId, Long userId);

    /** 管理端消息记录只读分页（REQ-087「后台记录」；按用户/领域/发送状态筛选）。 */
    PageDataVo<WsMessage> pageRecords(com.jbk.tool.data.message.bo.WsMessageRecordBo bo);

    /**
     * 标记已读：READ_FLAG 0→1 条件更新幂等（重复标记零变化仍成功）；
     * 越权与不存在同文案拒绝。
     */
    void markRead(Long messageId, Long userId);

    /**
     * 骨架·重试占位：SEND_STATUS 3发送失败→2发送中，条件更新（仅失败态可进入重试）。
     *
     * <p><b>调用边界（本方法族三个骨架方法同）：仅限受信任的调度/领域服务调用，禁止直挂 HTTP</b>——
     * 方法只收 messageId、无操作者上下文与鉴权，一旦暴露为端点即构成任意消息状态篡改面。</p>
     *
     * @return 是否真正发生迁移（false=当前不是失败态，含重复调用）
     */
    boolean markRetrySending(Long messageId);

    /**
     * 骨架·重试收尾：SEND_STATUS 2发送中→4已送达（success，SEND_TIME 取当下）
     * 或 2→3 回到失败态（!success）。条件更新，仅发送中可收尾。
     */
    boolean completeRetry(Long messageId, boolean success);

    /**
     * 骨架·降级为站内：失败态消息改渠道=站内并置已送达（SEND_TIME 取降级时刻——
     * 用户此刻才真正可见）。条件更新（仅 SEND_STATUS=3 可降级），重复调用零变化。
     */
    boolean degradeToInApp(Long messageId);
}
