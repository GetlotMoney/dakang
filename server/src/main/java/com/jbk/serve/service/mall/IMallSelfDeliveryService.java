package com.jbk.serve.service.mall;

import com.jbk.tool.data.mall.bo.MallFulfillActionBo;
import com.jbk.tool.data.mall.bo.MallFulfillAssignBo;
import com.jbk.tool.data.mall.vo.MallCourierCandidateVo;
import com.jbk.tool.data.mall.vo.MallFulfillVo;

import java.util.List;

/**
 * 自营配送渠道服务（E2E-09 L1）。
 *
 * <p>本服务只处理 {@code FULFILL_MODE = 1} 的任务。第三方任务不得出现在配送员列表里，
 * 也不得接受取货/送达——配送员手上出现一个他永远碰不到的包裹，
 * 比看不到它更糟：他会以为是系统卡了，然后去催。</p>
 *
 * <p>分配动作同时承担**渠道冻结**：CAS 0→1 与第三方建单的 CAS 0→2 互斥，
 * 两者并发只有一方能成功。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
public interface IMallSelfDeliveryService {

    /** PC 分配自营配送员（同事务冻结渠道为自营）。 */
    MallFulfillVo assign(Long operatorId, MallFulfillAssignBo bo);

    /** PC 可分配配送员候选：候选不是授权，服务端分配时仍会重新校验四条判据。 */
    List<MallCourierCandidateVo> courierCandidates(Long operatorId, String orderNo);

    /** 配送端取货。 */
    MallFulfillVo fetch(Long courierUserId, MallFulfillActionBo bo);

    /** 配送端送达（送达 ≠ 完成：订单完成恒由用户确认收货落定）。 */
    MallFulfillVo arrive(Long courierUserId, MallFulfillActionBo bo);

    /** 配送端我的任务列表：只含自营任务。 */
    List<MallFulfillVo> listForCourier(Long courierUserId);

    /** 配送端任务详情：只含自营任务。 */
    MallFulfillVo detailForCourier(Long courierUserId, String orderNo);
}
