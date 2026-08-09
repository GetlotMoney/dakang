package com.jbk.serve.mapper.minientry;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.minientry.po.WsMiniEntryConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 小程序入口配置 Mapper（S6；同键恒一行由 uk_mini_entry_key 保证）。
 *
 * @author dakang
 * @since 2026-08-07
 */
@Mapper
public interface WsMiniEntryConfigMapper extends BaseMapper<WsMiniEntryConfig> {
}
