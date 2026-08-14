package com.jbk.serve.service.user;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.user.bo.WsUserProfileBo;
import com.jbk.tool.data.user.vo.WsUserAuditVo;
import com.jbk.tool.data.user.vo.WsUserFlowVo;
import com.jbk.tool.data.user.vo.WsUserOrderVo;
import com.jbk.tool.data.user.vo.WsUserRelationItemVo;
import com.jbk.tool.data.user.vo.WsUserRelationVo;
import com.jbk.tool.data.user.vo.WsUserVo;

/**
 * 用户档案只读聚合服务（一人一档：身份资料 / 水卡与授权 / 订单 / 资金流水 / 关系归属 / 审计记录）。
 *
 * <p>全部方法只读，没有任何写路径：资产、状态、关系在本服务里都只是被观察的事实。
 * 每个分区独立分页，调用方按需取用，不提供「一次返回整份档案」的聚合方法——
 * 那等于把订单与流水的全量拉取藏进一个看起来很无害的详情接口里。</p>
 *
 * @author dakang
 * @since 2026-08-13
 */
public interface IWsUserProfileService {

    /** 身份资料（手机号已脱敏，不含身份密文与微信身份键）。 */
    WsUserVo getIdentity(Long userId);

    /** 订单分区分页（倒序，仅本人下单的订单）。 */
    PageDataVo<WsUserOrderVo> pageOrders(WsUserProfileBo bo);

    /** 资金流水分区分页（本人名下水卡的权威流水，只读快照）。 */
    PageDataVo<WsUserFlowVo> pageFlows(WsUserProfileBo bo);

    /** 关系归属概览：上一级邀请人 + 直接下级计数，不做递归。 */
    WsUserRelationVo getRelation(Long userId);

    /** 直接下级分页（一级，不可展开）。 */
    PageDataVo<WsUserRelationItemVo> pageInvitees(WsUserProfileBo bo);

    /** 审计记录分区分页（该用户发起的状态级变更痕迹，不含事件报文）。 */
    PageDataVo<WsUserAuditVo> pageAudits(WsUserProfileBo bo);
}
