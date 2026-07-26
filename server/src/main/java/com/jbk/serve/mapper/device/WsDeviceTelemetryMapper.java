package com.jbk.serve.mapper.device;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.device.po.WsDeviceTelemetry;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备遥测 Mapper
 *
 * @author dakang
 * @since 2026-07-12
 */
@Mapper
public interface WsDeviceTelemetryMapper extends BaseMapper<WsDeviceTelemetry> {

}
