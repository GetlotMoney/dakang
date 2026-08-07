package com.jbk.serve.mapper.settlement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.settlement.po.WsSplitComponent;
import org.apache.ibatis.annotations.Mapper;

/**
 * WsSplitComponent Mapper（E2E-08 S1）。V2 组件与计划的读写走 MyBatis-Plus 通用能力，
 * 不在此写 SQL——公式只在 SplitPlanCalculator，校验只在 SplitPlanSnapshot。
 *
 * @author dakang
 * @since 2026-08-06
 */
@Mapper
public interface WsSplitComponentMapper extends BaseMapper<WsSplitComponent> {
}
