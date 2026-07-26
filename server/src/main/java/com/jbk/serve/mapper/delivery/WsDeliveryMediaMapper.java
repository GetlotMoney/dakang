package com.jbk.serve.mapper.delivery;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.delivery.po.WsDeliveryMedia;
import org.apache.ibatis.annotations.Mapper;

/**
 * 配送受控媒体 Mapper。任务绑定通过条件 UPDATE（WHERE BOUND_TASK_ID IS NULL）
 * 原子占用，防同一照片跨任务复用。
 *
 * @author dakang
 * @since 2026-07-23
 */
@Mapper
public interface WsDeliveryMediaMapper extends BaseMapper<WsDeliveryMedia> {
}
