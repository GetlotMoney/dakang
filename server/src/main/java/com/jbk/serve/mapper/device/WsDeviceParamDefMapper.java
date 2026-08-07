package com.jbk.serve.mapper.device;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.device.po.WsDeviceParamDef;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备参数定义 Mapper（REQ-213）
 *
 * @author dakang
 * @since 2026-08-04
 */
@Mapper
public interface WsDeviceParamDefMapper extends BaseMapper<WsDeviceParamDef> {
}
