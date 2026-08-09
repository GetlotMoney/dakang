package com.jbk.serve.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.user.po.WsCourier;
import org.apache.ibatis.annotations.Mapper;

/**
 * 配送员 Mapper
 *
 * @author dakang
 * @since 2026-07-12
 */
@Mapper
public interface WsCourierMapper extends BaseMapper<WsCourier> {

    /**
     * 自助提交的并发串行锚（S3）：ws_courier 无 USER_ID 唯一键（历史允许多条准入记录），
     * 「读最新→判分支→插入/复用」窗口内两个并发提交会各插一条。锁本人 ws_user 行把
     * 同一用户的提交串行化——后到者等锁后重读，命中先到者刚写入的待审核记录即被拒绝。
     */
    @org.apache.ibatis.annotations.Select("SELECT ID FROM ws_user WHERE ID = #{userId} FOR UPDATE")
    Long lockUserRow(Long userId);
}
