package com.jbk.serve.mapper.settlement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.settlement.po.WsRegionAgent;
import org.apache.ibatis.annotations.Mapper;

/**
 * WsRegionAgent Mapper。归属版本的选取逻辑只在 {@code RegionAgentResolver}，
 * 不在此写 SQL——「取 EFFECT_TIME &lt;= 订单时间的最大一行」这条规则散到多处即会漂移。
 *
 * @author dakang
 * @since 2026-08-12
 */
@Mapper
public interface WsRegionAgentMapper extends BaseMapper<WsRegionAgent> {
}
