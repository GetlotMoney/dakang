package com.jbk.serve.mapper.settlement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.settlement.po.WsSplitClawbackAction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 分润冲减动作级 outbox Mapper（D-420 R2）。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Mapper
public interface WsSplitClawbackActionMapper extends BaseMapper<WsSplitClawbackAction> {

    /**
     * 登记撞键后的等价核验读：锁定读保证在 RR 事务快照下也看见已提交的既有登记
     * （撞键即证明该行已提交）。调用方持客户退款事务，本行是叶子锁，无对向持锁方。
     */
    @Select("SELECT * FROM ws_split_clawback_action WHERE ACTION_ID = #{actionId} AND DATA_STATUS = 0 FOR UPDATE")
    WsSplitClawbackAction lockByActionIdForUpdate(Long actionId);

    /**
     * Worker 发现源（R2-P0-2）：待处理动作直接来自登记行——明细事实缺失/被改
     * 也不影响动作被发现并进入对账。
     */
    @Select("SELECT ACTION_ID FROM ws_split_clawback_action WHERE OUTBOX_STATUS = 1 AND DATA_STATUS = 0 ORDER BY ID LIMIT 50")
    List<Long> scanPendingActionIds();
}
