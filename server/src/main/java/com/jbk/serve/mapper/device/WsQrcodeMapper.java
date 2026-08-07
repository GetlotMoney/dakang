package com.jbk.serve.mapper.device;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.device.po.WsQrcode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 设备二维码 Mapper
 *
 * @author dakang
 * @since 2026-07-12
 */
@Mapper
public interface WsQrcodeMapper extends BaseMapper<WsQrcode> {


    /**
     * 当前读二维码行（{@code LOCK IN SHARE MODE}）：共键重核必须与设备/出水口取自同一批当前数据，
     * 否则「码在事务中途被改绑」在快照里看不见。二维码在本事务只读，用共享锁。
     *
     * @param qrcodeId 二维码ID
     * @return 未删除的二维码行；不存在或已删除返回 null
     */
    @Select("SELECT * FROM ws_qrcode WHERE ID = #{qrcodeId} AND DATA_STATUS = 0 LOCK IN SHARE MODE")
    WsQrcode selectByIdForShare(@Param("qrcodeId") Long qrcodeId);
}
