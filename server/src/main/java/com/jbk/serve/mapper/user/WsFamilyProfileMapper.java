package com.jbk.serve.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.user.po.WsFamilyProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 家庭资料 Mapper（一人一份，uk_family_user 兜底并发建档）。
 *
 * @author dakang
 * @since 2026-08-02
 */
@Mapper
public interface WsFamilyProfileMapper extends BaseMapper<WsFamilyProfile> {
}
