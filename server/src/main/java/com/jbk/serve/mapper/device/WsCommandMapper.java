package com.jbk.serve.mapper.device;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.device.po.WsCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 设备指令 Mapper
 *
 * @author dakang
 * @since 2026-07-12
 */
@Mapper
public interface WsCommandMapper extends BaseMapper<WsCommand> {


    /**
     * 按指令号当前读并锁定指令行（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>ACK 事务不能把调用方传进来的可变 {@code WsCommand} 当作 orderId/deviceId/cmdType 的
     * 权威来源——那是入口处读到的旧快照，且是可写对象。事务边界内必须自己把行锁住重读一遍，
     * 后续所有核验都基于锁内值。逻辑删除行不返回。</p>
     *
     * @param cmdNo 平台指令号
     * @return 未删除的指令行；不存在或已删除返回 null
     */
    @Select("SELECT * FROM ws_command WHERE CMD_NO = #{cmdNo} AND DATA_STATUS = 0 FOR UPDATE")
    WsCommand selectByCmdNoForUpdate(@Param("cmdNo") String cmdNo);
}
