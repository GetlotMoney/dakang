package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.mapper.mall.WsMallAfterSaleMapper;
import com.jbk.serve.mapper.mall.WsMallRefundMapper;
import com.jbk.serve.service.mall.IMallAfterSaleService;
import com.jbk.serve.service.mall.IMallRefundFactService;
import com.jbk.serve.service.mall.IMallRefundSimService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.mall.po.WsMallAfterSale;
import com.jbk.tool.data.mall.po.WsMallRefund;
import com.jbk.tool.data.mall.po.WsMallRefundFact;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 商城模拟退款实现（E2E-09 S4，仅隔离环境启用）。
 *
 * <p>退款金额、退款单、交易号与订单号全部由服务端按售后单重读得出——入参只有售后单号。
 * 收下前端传来的退款金额等于把「退多少」交给调用方，而调用方是可以被伪造的。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "mall.refund-sim.enabled", havingValue = "true")
public class MallRefundSimServiceImpl implements IMallRefundSimService {

    /** 模拟退款事实键前缀：与通知/查单渠道命名空间不重叠。 */
    static final String SIM_EVENT_KEY_PREFIX = "MALLRSIM:";
    /** 模拟退款交易号前缀。 */
    static final String SIM_TRANSACTION_PREFIX = "RSIMTX-";
    /** 「谁发起了模拟退款」的审计幂等键前缀，与事务B 的推进审计分属不同命名空间。 */
    static final String SIM_AUDIT_KEY_PREFIX = "MALL_REFUND_SIM:";

    @Autowired
    private WsMallAfterSaleMapper afterSaleMapper;
    @Autowired
    private WsMallRefundMapper refundMapper;
    @Autowired
    private IMallRefundFactService refundFactService;
    @Autowired
    private IMallAfterSaleService afterSaleService;
    @Autowired
    private IWsDomainEventService domainEventService;

    @Override
    public void refund(Long operatorId, String afterSaleNo) {
        // 归属闸必须在任何资金动作之前：本类是 PC 六个出口里唯一会把钱推出去的一个，
        // 漏掉这道闸就意味着任意管理端登录态都能对别人仓的售后单完成出账，
        // 而控制器事后那次 detailForManage 只会在钱已经退掉之后抛一个错误提示。
        afterSaleService.detailForManage(operatorId, afterSaleNo);
        WsMallAfterSale afterSale = afterSaleMapper.selectByNoIncludingDeleted(afterSaleNo);
        if (ObjectUtil.isNull(afterSale) || !ObjectUtil.equal(afterSale.getDataStatus(), 0)) {
            throw new JbkException("售后单不存在");
        }
        WsMallRefund refund = refundMapper.selectByAfterSaleIncludingDeleted(afterSale.getId());
        if (ObjectUtil.isNull(refund) || !ObjectUtil.equal(refund.getDataStatus(), 0)) {
            throw new JbkException("退款单尚未生成，无法发起退款");
        }
        int source = refund.getRefundSource();
        int channel = MallEnum.RefundFactChannel.REFUND_SIM.getValue();
        String eventKey = SIM_EVENT_KEY_PREFIX + refund.getRefundNo();

        // 先查后建：同一笔模拟退款只有一个成功时间，重复点击不得各自生成一份「现在」
        WsMallRefundFact fact = refundFactService.findFact(source, channel, eventKey);
        if (ObjectUtil.isNull(fact)) {
            fact = refundFactService.recordFact(source, channel, eventKey,
                    refund.getRefundNo(), refund.getOrderNo(),
                    MallEnum.RefundState.SUCCESS,
                    SIM_TRANSACTION_PREFIX + refund.getRefundNo(),
                    refund.getRefundAmountFen(), DateUtils.time(),
                    MallEnum.VerifyMethod.PAY_SIM_INTERNAL,
                    "{\"channel\":\"refund-sim\",\"refundNo\":\"" + refund.getRefundNo() + "\"}");
        }
        // 事务B 的审计以 SYSTEM 落库（推进由渠道事实驱动，不是某个人的动作）；
        // 「谁按下了模拟退款」是另一件事，必须单独留一条带真实操作人的证据，
        // 否则事后只剩控制器日志能说明这笔出账是谁发起的。
        domainEventService.recordReliableOnceAs(OpsEnum.ActorPortal.MANAGE, operatorId,
                OpsEnum.EventType.MALL, "MALLAFTERSALE:" + afterSaleNo,
                SIM_AUDIT_KEY_PREFIX + refund.getRefundNo(),
                "发起模拟退款", "退款单 " + refund.getRefundNo() + "，金额 "
                        + refund.getRefundAmountFen() + " 分");
        refundFactService.process(fact.getId());
    }
}
