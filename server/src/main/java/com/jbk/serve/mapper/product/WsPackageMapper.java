package com.jbk.serve.mapper.product;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.product.po.WsPackage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 水卡套餐 Mapper（L2-READ 只读）。
 */
@Mapper
public interface WsPackageMapper extends BaseMapper<WsPackage> {
}
