package com.jbk.serve.mapper.device;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.device.po.WsFaultDict;
import org.apache.ibatis.annotations.Mapper;

/**
 * 故障码字典 Mapper
 *
 * @author dakang
 * @since 2026-07-12
 */
@Mapper
public interface WsFaultDictMapper extends BaseMapper<WsFaultDict> {

}
