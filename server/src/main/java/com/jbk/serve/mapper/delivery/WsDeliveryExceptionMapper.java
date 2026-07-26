package com.jbk.serve.mapper.delivery;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.delivery.po.WsDeliveryException;
import org.apache.ibatis.annotations.Mapper;

/**
 * 配送异常记录 Mapper（只插入不更新）。
 *
 * @author dakang
 * @since 2026-07-23
 */
@Mapper
public interface WsDeliveryExceptionMapper extends BaseMapper<WsDeliveryException> {
}
