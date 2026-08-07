package com.jbk.serve.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.user.po.WsCard;
import org.apache.ibatis.annotations.Mapper;

/**
 * 水卡 Mapper
 *
 * @author dakang
 * @since 2026-07-12
 */
@Mapper
public interface WsCardMapper extends BaseMapper<WsCard> {

    /**
     * 按卡号锁定读（审查维度2 P2）：赠卡发放撞 uk_card_no 的败方必须用当前读回看胜方
     * 刚提交的卡——REPEATABLE READ 下普通 SELECT 仍是本事务开头的旧快照，读不到就会
     * 误报「请求号冲突请更换」，运营照文案换号重提即双发（与 MiniAuthBindTxImpl 的
     * selectByOpenidForUpdate 同因同解）。
     */
    @org.apache.ibatis.annotations.Select(
            "SELECT * FROM ws_card WHERE CARD_NO = #{cardNo} AND DATA_STATUS = 0 FOR UPDATE")
    WsCard selectByCardNoForUpdate(@org.apache.ibatis.annotations.Param("cardNo") String cardNo);
}
