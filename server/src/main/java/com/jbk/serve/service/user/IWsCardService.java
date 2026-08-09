package com.jbk.serve.service.user;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.user.bo.WsCardBo;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.data.user.vo.WsCardVo;

import java.util.List;

/**
 * 水卡服务（一期口径：只读 + 冻结/解冻状态管理）
 *
 * @author dakang
 * @since 2026-07-12
 */
public interface IWsCardService extends IService<WsCard> {

    /**
     * 分页查询（卡号模糊/类型/状态/持卡用户筛选，派生持卡人与成员数）
     */
    PageDataVo<WsCardVo> pageData(WsCardBo cardBo);

    /**
     * 详情（含授权成员列表）
     */
    WsCardVo getData(Long id);

    /**
     * 按用户查卡（用户详情抽屉聚合展示）
     */
    List<WsCardVo> listByUser(Long userId);

    /**
     * 冻结/解冻（状态机：仅 1正常↔2冻结 允许后台手动流转；
     * 3已过期/4已注销 为系统终态不可手动改，REQ-075 卡状态影响刷卡授权）
     */
    Boolean changeStatus(WsCardBo cardBo);

    /**
     * 修改授权范围（S4）：服务端按结构化维度构造 SCOPE_JSON（禁止直接编辑原文），
     * WaterCardScope 唯一规范化校验；旧值 CAS 防并发覆盖；只影响后续扫码/下单/扣款
     * 校验，不追溯改写历史订单快照。前后快照与原因经 @LogOperation 进操作日志。
     */
    Boolean updateScope(com.jbk.tool.data.user.bo.WsCardScopeBo bo);
}
