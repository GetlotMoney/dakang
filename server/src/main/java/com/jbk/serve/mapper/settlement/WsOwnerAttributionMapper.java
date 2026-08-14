package com.jbk.serve.mapper.settlement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.settlement.po.WsOwnerAttribution;
import org.apache.ibatis.annotations.Mapper;

/**
 * WsOwnerAttribution Mapper。查询走 MP 条件构造器。
 *
 * @author dakang
 * @since 2026-08-14
 */
@Mapper
public interface WsOwnerAttributionMapper extends BaseMapper<WsOwnerAttribution> {
}
