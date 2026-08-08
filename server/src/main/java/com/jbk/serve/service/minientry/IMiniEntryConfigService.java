package com.jbk.serve.service.minientry;

import com.jbk.tool.data.minientry.po.WsMiniEntryConfig;

import java.util.List;

/**
 * 小程序入口与运营配置（S6 配置底座）。
 *
 * <p>状态机：草稿(1)/已撤回(3) 可修改与发布；发布(2) 只可撤回。全部流转带
 * VERSION CAS——并发发布只有一个有效版本。小程序只读已发布且启用的行；
 * 固定 Tabbar 与账号能力权限不受配置覆盖（前端路由合同与能力守卫仍然生效）。</p>
 */
public interface IMiniEntryConfigService {

    /** 管理侧全量列表（含草稿/撤回）。 */
    List<WsMiniEntryConfig> listForAdmin();

    /**
     * 保存草稿（新建或修改）：内部路由必须命中编号白名单；外链仅 https 且域名在
     * 配置白名单（默认空=全拒绝），javascript:/任意 scheme 直接拒绝；公告必须有正文。
     * 已发布行不可直接修改（先撤回）。
     */
    Long saveDraft(WsMiniEntryConfig bo, Integer expectedVersion);

    /** 发布：CAS 草稿/已撤回→已发布。 */
    void publish(Long id, Integer expectedVersion);

    /** 撤回：CAS 已发布→已撤回，小程序即刻不再下发。 */
    void retract(Long id, Integer expectedVersion);

    /** 小程序只读：已发布且启用，按 SORT_NO 升序稳定排列。 */
    List<WsMiniEntryConfig> listPublished();
}
