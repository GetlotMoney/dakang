package com.jbk.serve.mapper.settlement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.settlement.po.WsOwnerReferrer;
import org.apache.ibatis.annotations.Mapper;

/**
 * WsOwnerReferrer Mapper。查询走 MP 条件构造器；「机主→推荐人」的唯一消费方是
 * 分账 enqueue 与后台归属页，不在此扩散自定义 SQL。
 *
 * @author dakang
 * @since 2026-08-14
 */
@Mapper
public interface WsOwnerReferrerMapper extends BaseMapper<WsOwnerReferrer> {
}
