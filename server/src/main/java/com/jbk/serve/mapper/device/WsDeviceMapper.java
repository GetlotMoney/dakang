package com.jbk.serve.mapper.device;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.device.po.WsDevice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 设备 Mapper
 *
 * @author dakang
 * @since 2026-07-12
 */
@Mapper
public interface WsDeviceMapper extends BaseMapper<WsDevice> {

    /**
     * 当前读锁定设备行（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>REPEATABLE READ 下普通 SELECT 读的是事务首次一致性读建立的快照；取水下单事务在锁卡上
     * 可能等待较久，期间设备离线/维护/故障的提交对该快照不可见。资金动作之前判定设备可用性
     * 必须走当前读，否则判的是过期状态。逻辑删除行不返回——删除即不可用。</p>
     *
     * @param deviceId 设备ID
     * @return 未删除的设备行；不存在或已删除返回 null
     */
    @Select("SELECT * FROM ws_device WHERE ID = #{deviceId} AND DATA_STATUS = 0 FOR UPDATE")
    WsDevice selectByIdForUpdate(@Param("deviceId") Long deviceId);

    /**
     * 老板测试环境按水站统一设备机主归属。
     *
     * <p>使用显式 SQL 是为了让演示初始化器不依赖 MyBatis-Plus Lambda 元数据缓存的
     * 初始化顺序；完整 Spring 容器和独立单测因此执行同一条更新语义。</p>
     */
    @Update("""
            UPDATE ws_device
               SET OWNER_USER_ID = #{ownerUserId},
                   UPDATE_BY = #{actorId},
                   UPDATE_TIME = #{now}
             WHERE STATION_ID = #{stationId}
               AND DATA_STATUS = 0
               AND (OWNER_USER_ID IS NULL OR OWNER_USER_ID <> #{ownerUserId})
            """)
    int assignOwnerByStation(@Param("stationId") Long stationId,
            @Param("ownerUserId") Long ownerUserId,
            @Param("actorId") Long actorId,
            @Param("now") String now);
}
