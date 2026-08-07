package com.jbk.serve.service.aftersale;

import com.jbk.tool.data.aftersale.vo.AdminWaterAbnormalPreviewVo;

/**
 * 取水异常核账事务（E2E-04 包A，售后来源 {@code SourceType.WATER_ABNORMAL}）。
 *
 * <p><b>本服务是三条售后来源中唯一「零资金写入」的一条。</b>它不返还任何余额或水量，
 * 只在核验既有账本自洽之后把订单从 6异常待补偿 推进到终态，并留下一条零额度的台账动作行。
 * 「不加钱」不是实现约定，而是编译期约束：实现类不注入任何具备加钱能力的依赖，
 * 理由见 {@code WaterAbnormalReconcileTxServiceImpl} 类注释。</p>
 *
 * <p>方法只有两个，且刻意不提供「补退差」入口：状态 6 中确实<b>未</b>退过差的那一支
 * （出水指令链异常直接置位）需要真实资金返还，属后续包的职责；本包对它只能显式拒绝。
 * 在这里加一个「顺手补退」的方法，就等于让核账路径重新长出加钱能力。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface IWaterAbnormalReconcileTxService {

    /**
     * 核账依据预览（只读，无任何写入，也不留拒绝证据）。
     *
     * <p>给 PC 展示来源判别、计划/实际水量、已退差额度、建议目标状态与阻断原因。
     * 预览结论<b>不是</b>确认的授权：{@link #confirm} 会在事务内用同一份规则重新判定，
     * 绝不采信本方法的输出或前端回传值。</p>
     *
     * @param orderId 订单ID
     * @return 核账依据；订单不存在或不适用时同样返回对象，由 {@code blockReason} 说明原因
     */
    AdminWaterAbnormalPreviewVo preview(Long orderId);

    /**
     * 运营确认核账，推进订单终态。<b>绝不加钱加水。</b>
     *
     * <p>只有「已退差待复核」这一支可确认：零出水推进 6→7已退款，部分出水推进 6→4已完成，
     * 均为原子 CAS（WHERE 带 ID + ORDER_STATUS=6，影响行必须为 1）。未退差的一支、
     * 账本断裂（订单-指令共键错位、退差流水与计划-实际不符）与证据缺失一律拒绝并转人工，
     * 拒绝证据独立提交。</p>
     *
     * @param orderId      订单ID
     * @param handleRemark 运营核账说明，落审计与台账计算快照
     * @param opUserId     操作人（PC 员工ID），落 APPROVE_BY 与 UPDATE_BY
     * @param now          业务时钟 yyyyMMddHHmmss；由调用方给出，本服务不另取第二套时钟
     */
    void confirm(Long orderId, String handleRemark, Long opUserId, String now);
}
