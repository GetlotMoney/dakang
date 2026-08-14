package com.jbk.serve.service.mall;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallCheckoutBo;
import com.jbk.tool.data.mall.bo.MallOrderActionBo;
import com.jbk.tool.data.mall.bo.MallOrderQueryBo;
import com.jbk.tool.data.mall.vo.MallOrderDetailVo;
import com.jbk.tool.data.mall.vo.MallOrderVo;
import com.jbk.tool.data.mall.vo.MiniMallCheckoutVo;

/**
 * 商城订单服务（E2E-09 S2）。
 *
 * <p>小程序侧方法首参恒为 userId 并在 Service 内强制过滤数据范围；PC 侧只读，
 * 不提供任何推进状态机的入口——后台改单会绕过库存与支付事实，账就再也对不上。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
public interface IMallOrderService {

    /** 结算预览：只读试算，不预占库存（预占发生在创单事务）。 */
    MiniMallCheckoutVo preview(Long userId, MallCheckoutBo bo);

    /**
     * 创建订单：单一事务内选仓、重算金额、按锁序原子预占全部 SKU、写单与流水。
     * 同 requestId 同参重放返回原订单；改 SKU/数量/地址一律拒绝。
     */
    MallOrderVo create(Long userId, MallCheckoutBo bo);

    /** 本人订单分页。 */
    PageDataVo<MallOrderVo> pageForUser(Long userId, MallOrderQueryBo bo);

    /** 本人订单详情（含明细快照）。 */
    MallOrderDetailVo detailForUser(Long userId, String orderNo);

    /** 用户取消：仅待支付可取消，事务内按锁序释放全部预占。 */
    MallOrderVo cancelByUser(Long userId, MallOrderActionBo bo);

    /**
     * 超时关单（Worker 专用）：调用前必须已从支付方取得权威 CLOSED。
     * 本方法只做状态 CAS 与释放预占，不判断是否该关——是否该关由查单答复决定。
     */
    MallOrderVo closeByAuthority(String orderNo, String reason);

    /** PC 台账分页（只读）。 */
    PageDataVo<MallOrderVo> pageForAdmin(MallOrderQueryBo bo);

    /** PC 台账详情（只读）。 */
    MallOrderDetailVo detailForAdmin(String orderNo);
}
