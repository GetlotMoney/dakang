package com.jbk.serve.mapper.station;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.station.po.WsStation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 水站 Mapper
 *
 * @author dakang
 * @since 2026-07-12
 */
@Mapper
public interface WsStationMapper extends BaseMapper<WsStation> {

    /**
     * 按水站统计设备数（直接对 ws_device 计数，避免站点域反向依赖设备域 Po）
     *
     * @param stationIdList 水站ID集合
     * @return [{stationId, deviceCount}]
     */
    List<Map<String, Object>> countDeviceByStation(@Param("stationIdList") List<Long> stationIdList);
}
