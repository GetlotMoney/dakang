package com.jbk.serve.mapper.device;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 设备出水口 Mapper
 *
 * @author dakang
 * @since 2026-07-12
 */
@Mapper
public interface WsDeviceOutletMapper extends BaseMapper<WsDeviceOutlet> {

    /**
     * 当前读锁定出水口行（{@code SELECT ... FOR UPDATE}），与
     * {@link WsDeviceMapper#selectByIdForUpdate} 同一理由：事务内判定停用状态必须读当前值。
     * 锁顺序固定「设备 → 出水口」，调用方不得反向。
     *
     * @param outletId 出水口ID
     * @return 未删除的出水口行；不存在或已删除返回 null
     */
    @Select("SELECT * FROM ws_device_outlet WHERE ID = #{outletId} AND DATA_STATUS = 0 FOR UPDATE")
    WsDeviceOutlet selectByIdForUpdate(@Param("outletId") Long outletId);
}
