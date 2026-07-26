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
}
