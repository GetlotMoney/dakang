package com.jbk.serve.mapper.device;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.device.po.WsCommand;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备指令 Mapper
 *
 * @author dakang
 * @since 2026-07-12
 */
@Mapper
public interface WsCommandMapper extends BaseMapper<WsCommand> {

}
