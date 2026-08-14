package com.jbk.serve.service.settlement;

import com.jbk.tool.data.settlement.bo.OwnerAttributionBo;
import com.jbk.tool.data.settlement.po.WsOwnerAttribution;
import com.jbk.tool.data.settlement.po.WsOwnerReferrer;

import java.util.Optional;

/**
 * 分润归属载体（D-404/D-406/D-407/D-428）：机主加盟推荐关系与区域归属链的
 * 录入与查询。两张表都是「一人一行、建立即冻结」——没有更新与删除入口，
 * 录错走后续申诉流程（REQ-058，未开放），不给"顺手改历史"留门。
 *
 * @author dakang
 * @since 2026-08-14
 */
public interface IOwnerAttributionService {

    /**
     * 录入机主加盟推荐关系（后台，来源 ADMIN_ENTRY）。
     * 校验：机主/推荐人都存在、不得自荐、机主未有既存关系（冻结）。
     *
     * @return 新行 ID
     */
    Long createReferrer(OwnerAttributionBo bo, Long opUserId);

    /**
     * 录入机主区域归属链。三级可任意留空（缺席语义见 D-428），在场者互不相同且不得
     * 是机主本人兼任自身链上的运营中心；机主未有既存链（冻结）。
     *
     * @return 新行 ID
     */
    Long createAttribution(OwnerAttributionBo bo, Long opUserId);

    /** 机主的直接推荐人；无关系返回 empty（份额自然落平台）。 */
    Optional<WsOwnerReferrer> referrerOf(Long ownerUserId);

    /** 机主的区域归属链；无行=公域未分配。 */
    Optional<WsOwnerAttribution> attributionOf(Long ownerUserId);
}
