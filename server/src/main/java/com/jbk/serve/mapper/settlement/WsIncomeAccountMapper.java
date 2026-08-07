package com.jbk.serve.mapper.settlement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.settlement.po.WsIncomeAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * WsIncomeAccount Mapper（E2E-08 包B）。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Mapper
public interface WsIncomeAccountMapper extends BaseMapper<WsIncomeAccount> {

    /**
     * 按收益人锁行读（FOR UPDATE）。分润入账走行锁而非乐观 CAS：Worker 与提现并发时
     * REPEATABLE READ 下 CAS 重试读到的仍是旧快照，重试注定全败；锁定读恒取最新已提交值，
     * 一次成功（uk_income_account_user 保证至多一行）。
     */
    @Select("SELECT * FROM ws_income_account WHERE USER_ID = #{userId} AND DATA_STATUS = 0 FOR UPDATE")
    WsIncomeAccount selectByUserIdForUpdate(@Param("userId") Long userId);
}
