package com.jbk.serve.mapper.message;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.message.po.WsMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内消息 Mapper（E2E-03 A6）。
 *
 * @author dakang
 * @since 2026-07-23
 */
@Mapper
public interface WsMessageMapper extends BaseMapper<WsMessage> {
}
