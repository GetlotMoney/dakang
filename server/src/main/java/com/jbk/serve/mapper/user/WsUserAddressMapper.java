package com.jbk.serve.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.user.po.WsUserAddress;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 地址簿 Mapper。常规读写走 BaseMapper（@TableLogic 生效，读写恒带会话人 USER_ID 条件）；
 * 默认位互斥用手写 UPDATE 在事务内先清后设。
 *
 * @author dakang
 * @since 2026-08-02
 */
@Mapper
public interface WsUserAddressMapper extends BaseMapper<WsUserAddress> {

    /** 清掉该用户全部默认位（设新默认前调用；同事务内随后 update 目标行=1）。 */
    @Update("UPDATE ws_user_address SET IS_DEFAULT = 0, UPDATE_BY = #{actor}, UPDATE_TIME = #{time} "
            + "WHERE USER_ID = #{userId} AND DATA_STATUS = 0 AND IS_DEFAULT = 1")
    int clearDefault(@Param("userId") Long userId, @Param("actor") Long actor, @Param("time") String time);
}
