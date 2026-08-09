package com.jbk.serve.mapper.settlement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.settlement.po.WsSplitClawback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 分润冲减明细事实 Mapper（D-420 R2：明细由执行事务生成，发现源在动作级 outbox）。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Mapper
public interface WsSplitClawbackMapper extends BaseMapper<WsSplitClawback> {

    /**
     * 执行段锁定读：按动作锁全部明细行（固定 ID 序），与分账行锁配合把
     * 「同订单多动作并发执行」串行化，对账在锁内恒对最新事实。
     */
    @Select("SELECT * FROM ws_split_clawback WHERE ACTION_ID = #{actionId} AND DATA_STATUS = 0 ORDER BY ID FOR UPDATE")
    List<WsSplitClawback> lockByActionForUpdate(Long actionId);

    /**
     * 行累计已登记冲减，<b>排除本动作</b>（R2-P1-1）：重放/对账时本动作既有明细
     * 不计入累计基数，否则整额退款重放会自撞上限。执行段锁内调用。
     */
    @Select("SELECT COALESCE(SUM(CLAWBACK_AMOUNT),0) FROM ws_split_clawback WHERE SPLIT_ID = #{splitId} AND ACTION_ID != #{actionId} AND DATA_STATUS = 0")
    Long sumBySplitExcludingAction(@Param("splitId") Long splitId, @Param("actionId") Long actionId);
}
