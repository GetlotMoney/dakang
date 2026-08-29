package com.jbk.serve.mapper.identity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.identity.po.WsDemoControl;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WsDemoControlMapper extends BaseMapper<WsDemoControl> {

    @Select("SELECT * FROM ws_demo_control WHERE USER_ID = #{userId} AND DATA_STATUS = 0 FOR UPDATE")
    WsDemoControl selectByUserIdForUpdate(Long userId);

    @Select("SELECT * FROM ws_demo_control WHERE USER_ID = #{userId} AND DATA_STATUS = 0")
    WsDemoControl selectByUserId(Long userId);

    @Update("UPDATE ws_demo_control SET NEXT_PAY_RESULT = 'SUCCESS'"
            + " WHERE ID = #{id} AND NEXT_PAY_RESULT = #{expected} AND DATA_STATUS = 0")
    int resetPayResult(Long id, String expected);

    @Update("UPDATE ws_demo_control SET NEXT_DEVICE_RESULT = 'NORMAL'"
            + " WHERE ID = #{id} AND NEXT_DEVICE_RESULT = #{expected} AND DATA_STATUS = 0")
    int resetDeviceResult(Long id, String expected);
}
