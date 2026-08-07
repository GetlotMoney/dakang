package com.jbk.serve.mapper.station;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.station.po.WsStation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    /**
     * 当前读水站行（{@code LOCK IN SHARE MODE}）：设备迁站是共键判定的活口，快照读看不到迁站结果。
     * 一个水站挂多台设备，用共享锁避免把全站下单串行化。
     *
     * @param stationId 水站ID
     * @return 未删除的水站行；不存在或已删除返回 null
     */
    @Select("SELECT * FROM ws_station WHERE ID = #{stationId} AND DATA_STATUS = 0 LOCK IN SHARE MODE")
    WsStation selectByIdForShare(@Param("stationId") Long stationId);
}
