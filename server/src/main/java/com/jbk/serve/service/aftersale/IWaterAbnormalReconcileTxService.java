package com.jbk.serve.service.aftersale;

import com.jbk.tool.data.aftersale.vo.AdminWaterAbnormalPreviewVo;

/**
 * 取水异常核账事务（E2E-04 包A，售后来源 {@code SourceType.WATER_ABNORMAL}）：
 * 唯一「零资金写入」的售后来源——只核验账本自洽后把订单从 6 推进终态并留零额度台账行。
 * 「不加钱」是编译期约束（实现类不注入加钱依赖）；刻意不提供「补退差」入口，
 * 未退差的一支属后续包，本包只能显式拒绝。
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface IWaterAbnormalReconcileTxService {

    /**
     * 核账依据预览（只读，不写入、不留拒绝证据）。预览结论不是确认的授权：
     * {@link #confirm} 会在事务内用同一份规则重判，绝不采信本方法输出或前端回传值。
     *
     * @param orderId 订单ID
     * @return 核账依据；订单不存在或不适用时同样返回对象，由 {@code blockReason} 说明原因
     */
    AdminWaterAbnormalPreviewVo preview(Long orderId);

    /**
     * 运营确认核账，推进订单终态，绝不加钱加水。只有「已退差待复核」一支可确认
     * （零出水 6→7、部分出水 6→4，原子 CAS）；未退差、账本断裂与证据缺失一律拒绝转人工，
     * 拒绝证据独立提交。
     *
     * @param orderId      订单ID
     * @param handleRemark 运营核账说明，落审计与台账计算快照
     * @param opUserId     操作人（PC 员工ID），落 APPROVE_BY 与 UPDATE_BY
     * @param now          业务时钟 yyyyMMddHHmmss；由调用方给出，本服务不另取第二套时钟
     */
    void confirm(Long orderId, String handleRemark, Long opUserId, String now);
}
