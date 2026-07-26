package com.jbk.serve.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.jbk.tool.data.user.po.WsUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * C 端用户 Mapper。
 */
@Mapper
public interface WsUserMapper extends BaseMapper<WsUser> {

}
