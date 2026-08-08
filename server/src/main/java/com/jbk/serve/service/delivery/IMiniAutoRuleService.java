package com.jbk.serve.service.delivery;

import com.jbk.tool.data.mini.vo.MiniAutoRuleVo;

import java.util.List;

/**
 * 自动补货规则用户自助管理（S2）。
 *
 * <p>数据范围铁律：全部操作以会话 userId 为唯一主体——查询按本人过滤，
 * 状态操作以「ID+USER_ID+精确前态」三键 CAS，他人规则与不存在规则同为影响
 * 0 行，统一报「规则不存在」（不泄露存在性）。</p>
 *
 * <p>状态机：1启用 ⇄ 2停用（pause/resume），1/2 → 3已取消（cancel，终态不可恢复）。
 * Worker 侧配合：扫描只取启用；期次创单事务内锁定复核（{@code createAutoRefillPeriodOrder}），
 * 取消后绝不生成新订单。</p>
 */
public interface IMiniAutoRuleService {

    /** 本人规则列表（含下次到期时间与最近执行结果）。 */
    List<MiniAutoRuleVo> listMine(Long userId);

    /** 暂停：CAS 启用→停用。 */
    void pause(Long userId, Long ruleId);

    /** 恢复：CAS 停用→启用（已取消永不匹配前态，天然不可恢复）。 */
    void resume(Long userId, Long ruleId);

    /** 取消：CAS 启用/停用→已取消（终态）。 */
    void cancel(Long userId, Long ruleId);

    /**
     * 后台只读分页（S2 R1）：客服追踪用户规则与状态。只下发页面使用字段，
     * 电话脱敏、创建幂等键不出接口；启停与取消是用户自助动作，后台不代操作。
     */
    com.jbk.tool.data.PageDataVo<com.jbk.tool.data.delivery.vo.AdminAutoRuleVo> pageForAdmin(
            Long userId, Integer ruleStatus, long current, long size);
}
