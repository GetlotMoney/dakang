package com.jbk.serve.mapper.trade;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 钱包流水 Mapper（只插入不更新）
 *
 * @author dakang
 * @since 2026-07-19
 */
@Mapper
public interface WsWalletFlowMapper extends BaseMapper<WsWalletFlow> {

}
