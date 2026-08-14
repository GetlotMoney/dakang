package com.jbk.serve.mapper.device;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.device.po.WsDeviceParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 设备参数快照 Mapper（REQ-213）
 *
 * @author dakang
 * @since 2026-08-04
 */
@Mapper
public interface WsDeviceParamMapper extends BaseMapper<WsDeviceParam> {

    /**
     * 单语句 upsert，带单调守卫：只有更新的指令（SOURCE_CMD_ID 更大）才能覆盖。必须单语句——两段式并发会在唯一键插入意向锁上互等死锁（实测）。
     * 判据是 SOURCE_CMD_ID 大小而非 CMD_NO 相等：REQ-041 断网补传/重投会让更早指令的 result 后到，按到达顺序写会新值被旧值覆盖；
     * 排序基准取 ws_command.ID（真实下发顺序），绝不取设备回报时间戳。
     * SOURCE_CMD_ID 赋值必须放最后：ON DUPLICATE KEY UPDATE 自左向右求值，前面的 IF 都要读旧值比较。
     * 受影响行数不作为生效信号（found-rows 语义下匹配但零变更同样返回 1），生效与否以数据为准。
     */
    @Update("""
            INSERT INTO ws_device_param
              (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME,
               DEVICE_ID, PARAM_KEY, PARAM_VALUE, PARAM_VERSION,
               SOURCE_CMD_NO, SOURCE_CMD_ID, SYNC_TIME, REGISTERED_FLAG)
            VALUES
              (0, #{actorId}, #{now}, #{actorId}, #{now},
               #{deviceId}, #{paramKey}, #{paramValue}, 1,
               #{cmdNo}, #{cmdId}, #{syncTime}, #{registeredFlag})
            ON DUPLICATE KEY UPDATE
              PARAM_VALUE     = IF(SOURCE_CMD_ID < VALUES(SOURCE_CMD_ID), VALUES(PARAM_VALUE), PARAM_VALUE),
              PARAM_VERSION   = IF(SOURCE_CMD_ID < VALUES(SOURCE_CMD_ID), PARAM_VERSION + 1, PARAM_VERSION),
              SYNC_TIME       = IF(SOURCE_CMD_ID < VALUES(SOURCE_CMD_ID), VALUES(SYNC_TIME), SYNC_TIME),
              REGISTERED_FLAG = IF(SOURCE_CMD_ID < VALUES(SOURCE_CMD_ID), VALUES(REGISTERED_FLAG), REGISTERED_FLAG),
              UPDATE_BY       = IF(SOURCE_CMD_ID < VALUES(SOURCE_CMD_ID), VALUES(UPDATE_BY), UPDATE_BY),
              UPDATE_TIME     = IF(SOURCE_CMD_ID < VALUES(SOURCE_CMD_ID), VALUES(UPDATE_TIME), UPDATE_TIME),
              DATA_STATUS     = IF(SOURCE_CMD_ID < VALUES(SOURCE_CMD_ID), 0, DATA_STATUS),
              SOURCE_CMD_NO   = IF(SOURCE_CMD_ID < VALUES(SOURCE_CMD_ID), VALUES(SOURCE_CMD_NO), SOURCE_CMD_NO),
              SOURCE_CMD_ID   = IF(SOURCE_CMD_ID < VALUES(SOURCE_CMD_ID), VALUES(SOURCE_CMD_ID), SOURCE_CMD_ID)
            """)
    int upsertSnapshot(@Param("deviceId") Long deviceId,
                       @Param("paramKey") String paramKey,
                       @Param("paramValue") String paramValue,
                       @Param("cmdNo") String cmdNo,
                       @Param("cmdId") Long cmdId,
                       @Param("syncTime") String syncTime,
                       @Param("registeredFlag") int registeredFlag,
                       @Param("actorId") Long actorId,
                       @Param("now") String now);
}
