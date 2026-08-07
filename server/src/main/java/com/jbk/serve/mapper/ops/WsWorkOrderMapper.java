package com.jbk.serve.mapper.ops;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.ops.po.WsWorkOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 运维工单 Mapper（E2E-05）。状态迁移不在此写 SQL——统一走
 * {@code WorkOrderServiceImpl} 的前态 CAS lambdaUpdate，保证迁移逻辑单一出处。
 *
 * @author dakang
 * @since 2026-07-30
 */
@Mapper
public interface WsWorkOrderMapper extends BaseMapper<WsWorkOrder> {
}
