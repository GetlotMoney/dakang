package com.jbk.serve.service.mall;

import com.jbk.tool.data.mall.po.WsMallFulfillment;
import com.jbk.tool.data.mall.po.WsMallShipment;
import com.jbk.tool.data.mall.vo.MallShipmentVo;

import java.util.List;

/**
 * 商城出库包裹服务（E2E-09 L1）——渠道无关的包裹生命周期。
 *
 * <p>它不认识「自营」或「第三方」这两个词的业务含义，只负责：建包裹、按共键校验后组装、
 * 按精确前态推进包裹状态。渠道特有的动作分别落在
 * {@code IMallSelfDeliveryService} 与 {@code IMallLogisticsService}。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
public interface IMallShipmentService {

    /**
     * 为履约总单建一个包裹（确定性幂等键；已存在即原样返回）。
     *
     * <p>调用方必须已经完成渠道冻结：包裹的渠道值取自总单，不由入参决定——
     * 让调用方传渠道，就等于允许建出一个与总单渠道分叉的包裹。</p>
     */
    WsMallShipment ensureShipment(WsMallFulfillment task, int direction, int seq,
                                  Long sourceAfterSaleId, String providerCode, Long operator,
                                  String now);

    /** 按订单号读包裹（含明细与第三方轨迹），供管理端展示。 */
    List<MallShipmentVo> listByOrderNo(String orderNo);

    /**
     * 用户端读本人订单的包裹。
     *
     * <p>归属校验放在服务里而不是控制器：控制器只有一处会漏，服务被复用几次就漏几次。
     * 归属恒取会话 userId，不接受任何入参身份。</p>
     */
    List<MallShipmentVo> listForUser(Long userId, String orderNo);

    /**
     * 推进结论：把「没推进」拆成两种，因为它们的处置完全相反。
     *
     * <p>{@code MOVED} 真的推进了；{@code STALE} 是重放或乱序（包裹已在同态或更后的状态），
     * 事实可以如实记为已处理；{@code LOST} 是前态仍朝前、但 CAS 被另一条并发事实抢走了，
     * 这条事实**一步也没生效**，必须重投而不是标成已处理——标成已处理它就永远消失了。</p>
     */
    enum Advance {
        MOVED, STALE, LOST
    }

    /** 按包裹推进状态：前态精确、只许前进；返回三态结论。 */
    Advance advance(WsMallShipment shipment, int toStatus, String timeColumn, Long operator,
                    String now);

    /** 按包裹 ID 推进：调用方只有 ID 时用，避免各处自己拼 PO 造成前态取自陈旧副本。 */
    Advance advanceById(Long shipmentId, int toStatus, String timeColumn, Long operator,
                        String now);
}
