package com.jbk.serve.service.delivery;

import com.jbk.tool.consts.delivery.DeliveryEnum;

import java.util.List;

/**
 * 配送受控媒体服务（E2E-03 A5 最小实现：受控引用存库 + 本地存储适配器，不接对象存储）。
 *
 * <p>上传 HTTP 入口属于包B页面职责；本包只冻结 Service 契约与存储约定：
 * 媒体先 register 换取受控 mediaKey，签收/举证只提交 mediaKey，
 * 提交时 claim 原子绑定任务并校验 归属/用途/未复用。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
public interface IDeliveryMediaService {

    /**
     * 登记媒体内容并返回受控媒体键（内容寻址派生，重复登记幂等返回同键）。
     *
     * @param ownerUserId 登记人（会话用户）
     * @param purpose     用途（跨用途引用会在 claim 阶段被拒绝）
     * @param content     图片字节（非空，≤10MB）
     * @param mimeType    image/* 之外一律拒绝
     * @param now         登记时间（yyyyMMddHHmmss）
     */
    String register(Long ownerUserId, DeliveryEnum.MediaPurpose purpose, byte[] content, String mimeType, String now);

    /**
     * 将一组媒体键原子绑定到任务（条件 UPDATE：MEDIA_KEY+OWNER+PURPOSE 匹配且未被占用）。
     * 任何一个键 未登记/非本人/用途不符/已被其他任务占用 即整体失败抛出，由调用方事务回滚。
     * 键列表必须两两不同——同一张照片充当两照同样是造假。
     *
     * @param failMessage 失败文案（按业务场景给出：三照/举证）
     */
    void claimForTask(List<String> mediaKeys, Long taskId, Long ownerUserId,
                      DeliveryEnum.MediaPurpose purpose, String failMessage);

    /**
     * 门户显式的登记（E2E-05 工单证据）：员工与小程序用户的 ID 数值可能相同，
     * 工单证据必须携带登记人门户（1管理端/2用户）参与键派生与归属校验。
     * 旧用途（1-3）继续走 {@link #register}（隐含门户=2），键派生不变，E2E-03 证据不受影响。
     */
    String registerAs(int ownerPortal, Long ownerUserId, DeliveryEnum.MediaPurpose purpose,
                      byte[] content, String mimeType, String now);

    /**
     * 门户显式的原子绑定：WHERE 追加 OWNER_PORTAL 匹配，防跨身份空间挪用同键媒体。
     * 工单场景 taskId 传工单ID（BOUND_TASK_ID 语义按用途区分，purpose 已在 WHERE 内隔离）。
     */
    void claimForTaskAs(int ownerPortal, List<String> mediaKeys, Long taskId, Long ownerUserId,
                        DeliveryEnum.MediaPurpose purpose, String failMessage);
}
