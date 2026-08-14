package com.jbk.serve.service.mall;

import com.jbk.tool.data.mall.bo.MallAfterSaleAbortBo;
import com.jbk.tool.data.mall.bo.MallAfterSaleApplyBo;
import com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo;
import com.jbk.tool.data.mall.bo.MallAfterSaleInspectBo;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallAfterSaleNoBo;
import com.jbk.tool.data.mall.bo.MallAfterSaleQueryBo;
import com.jbk.tool.data.mall.vo.MallAfterSaleVo;

import java.util.List;

/**
 * 商城售后服务（E2E-09 S4）：退货退款、同 SKU 换货、未拣货整单取消退款。
 * 金额只有一个来源：服务端按原订单不可变明细 单价×批准数量 算出，页面不提交金额、审核人改不了；
 * 退款与库存是两件事：质检结论各自决定，可重新销售才回库，不可销售照退不回库。
 *
 * @author dakang
 * @since 2026-08-10
 */
public interface IMallAfterSaleService {

    /** 用户申请售后：同 requestId 重放返回原单，改参一律拒绝。 */
    MallAfterSaleVo apply(Long userId, MallAfterSaleApplyBo bo);

    /** 用户取消：只允许取消仍在待审核的申请。 */
    MallAfterSaleVo cancelByUser(Long userId, MallAfterSaleNoBo bo);

    /** PC 审核：通过进入待退货（换货/退货）或直接退款（未拣货取消）；驳回即终态。 */
    MallAfterSaleVo audit(Long operatorId, MallAfterSaleAuditBo bo);

    /** PC 确认收到退货：待退货 → 待质检。 */
    MallAfterSaleVo confirmReceive(Long operatorId, MallAfterSaleNoBo bo);

    /** PC 质检：结论决定退款/回库/驳回，三者互不代替。 */
    MallAfterSaleVo inspect(Long operatorId, MallAfterSaleInspectBo bo);

    /**
     * PC 中止换货补发：释放换货预占、取消补发单，售后单交回人工。
     *
     * <p>没有这条出口时，「换货补发中」只有一个出口——补发单被签收。补发单一旦送不出去
     * （配送员停用、地址失效、货损），售后单永久停在 5，用户既拿不到换货也拿不到退款，
     * 那 N 件预占也永远不会释放。</p>
     */
    MallAfterSaleVo abortExchange(Long operatorId, MallAfterSaleAbortBo bo);

    /** 用户视角：本人售后列表。 */
    List<MallAfterSaleVo> listForUser(Long userId);

    /** 用户视角详情（本人售后）。 */
    MallAfterSaleVo detailForUser(Long userId, String afterSaleNo);

    /** PC 台账分页：可见范围由操作员的前置仓归属在服务端决定。 */
    PageDataVo<MallAfterSaleVo> pageForManage(Long operatorId, MallAfterSaleQueryBo bo);

    /** PC 视角详情（操作员须归属原履约仓）。 */
    MallAfterSaleVo detailForManage(Long operatorId, String afterSaleNo);
}
