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
     * 单语句 upsert，带<b>单调守卫</b>：只有比既有行更新的指令才能覆盖。
     *
     * <p><b>必须是单语句。</b>先 UPDATE 再兜底 INSERT 的两段式在两条 result 并发命中同一
     * (设备,键) 时会双双走到 INSERT，各自持有唯一键的插入意向锁互等，实测直接抛
     * {@code DeadlockLoserDataAccessException}。单语句由 InnoDB 内部收口，只有行等待没有环。</p>
     *
     * <p><b>判据是 SOURCE_CMD_ID 的大小，不是 SOURCE_CMD_NO 的相等。</b>相等只能挡住
     * 「同一条指令的重复 result」，挡不住「更早指令的 result 后到」——而后者是既有能力下的
     * 正常时序：REQ-041 断网补传走 replay 主题重放设备缓存的历史 result，与实时 result
     * 无全局顺序；重投通道也会把处理失败的消息再发一次。按到达顺序写的后果是新值被旧值覆盖、
     * SYNC_TIME 倒退，而 PARAM_VERSION 还 +1，让陈旧数据看起来更新。
     * 排序基准取 ws_command.ID（平台自增，即真实下发顺序），绝不取设备回报的时间戳。</p>
     *
     * <p>{@code SOURCE_CMD_ID = ...} 必须放在赋值列表<b>最后</b>：ON DUPLICATE KEY UPDATE
     * 的赋值自左向右求值，前面每一条 IF 都要读<b>旧</b>的 SOURCE_CMD_ID 做比较。</p>
     *
     * <p>受影响行数不作为「是否生效」的信号：驱动默认 found-rows 语义下，匹配但零变更同样返回 1。
     * 生效与否的事实以数据为准（版本、来源指令）。</p>
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
