package com.jbk.serve.mapper.delivery;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.delivery.po.WsDeliveryAutoRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 自动补货规则 Mapper（规则创建幂等由 uk_dauto_rule_key 保证）。
 *
 * @author dakang
 * @since 2026-07-23
 */
@Mapper
public interface WsDeliveryAutoRuleMapper extends BaseMapper<WsDeliveryAutoRule> {

    /**
     * 期次创单事务内的规则锁定读（S2）：取消/暂停与 Worker 扫描并发的裁决点。
     * 扫描快照读到 ENABLED 后用户可能已取消——创单资金事务内锁行复核当前状态，
     * 非启用即拒绝，保证「取消后绝不生成新订单」。
     */
    @org.apache.ibatis.annotations.Select(
            "SELECT * FROM ws_delivery_auto_rule WHERE ID = #{ruleId} AND DATA_STATUS = 0 FOR UPDATE")
    WsDeliveryAutoRule selectByIdForUpdate(Long ruleId);
}
