package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.mapper.mall.WsMallAfterSaleItemMapper;
import com.jbk.serve.mapper.mall.WsMallAfterSaleMapper;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.consts.message.MessageEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.mall.po.WsMallAfterSale;
import com.jbk.tool.data.mall.po.WsMallAfterSaleItem;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.exception.JbkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 换货补发的签收结算（E2E-09 S4）。
 *
 * <p>单独一个组件，是为了让 S3 履约服务里那一支只有一行调用：履约链不该知道换货的细节，
 * 换货也不该把自己的库存与售后收尾散进履约状态机里。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Slf4j
@Component
public class MallExchangeSettlement {

    private static final long SYSTEM_OPERATOR = 0L;

    @Autowired
    private WsMallAfterSaleMapper afterSaleMapper;
    @Autowired
    private WsMallAfterSaleItemMapper afterSaleItemMapper;
    @Autowired
    private MallAfterSaleStock afterSaleStock;
    @Autowired
    private MallAfterSaleTraceWriter traceWriter;
    @Autowired
    private IWsMessageService messageService;
    @Autowired
    private IWsDomainEventService domainEventService;

    /**
     * 补发单签收：换货实际出库 + 售后单收尾。
     *
     * <p>与签收同一事务：出库失败则整次签收回滚，绝不出现「用户已签收但库存还挂着预占」。</p>
     */
    public void settleOnSign(WsMallOrder reshipment, Long userId, String now) {
        WsMallAfterSale afterSale = afterSaleMapper.selectById(reshipment.getSourceAfterSaleId());
        if (ObjectUtil.isNull(afterSale) || !ObjectUtil.equal(afterSale.getDataStatus(), 0)) {
            throw new JbkException("补发单来源售后单不存在或已删除，签收已中止");
        }
        if (!ObjectUtil.equal(afterSale.getOrderId(), null)
                && ObjectUtil.equal(afterSale.getWarehouseId(), reshipment.getWarehouseId())) {
            // 仓一致是换货出库的前提：补发从哪个仓发出，就从哪个仓扣预占
            log.debug("换货补发签收结算 afterSaleNo={}", afterSale.getAfterSaleNo());
        }
        else {
            throw new JbkException("补发单与来源售后单共键不一致，请人工核查");
        }

        for (WsMallAfterSaleItem item : afterSaleItemMapper
                .selectByAfterSaleOrderBySku(afterSale.getId())) {
            afterSaleStock.exchangeOut(reshipment.getWarehouseId(), item.getSkuId(),
                    item.getQuantity(), afterSale.getAfterSaleNo(), userId, now);
        }

        int finished = afterSaleMapper.casFinish(afterSale.getId(),
                MallEnum.AfterSaleStatus.EXCHANGING.getValue(),
                MallEnum.AfterSaleStatus.COMPLETED.getValue(), afterSale.getVersion(),
                userId, now);
        if (finished != 1) {
            throw new JbkException("售后状态已变化，换货签收已中止");
        }
        // 轨迹即状态到达史（建表口径）：只写消息与审计而不落节点，售后详情的时间线
        // 就会永远停在「换货补发中」，用户与客服看不到换货究竟有没有完成。
        traceWriter.write(afterSale, MallEnum.AfterSaleStatus.COMPLETED,
                MallEnum.ActorType.USER, userId, null, now,
                "换货补发单 " + reshipment.getOrderNo() + " 已签收，换货完成");
        messageService.sendInApp(afterSale.getUserId(), MessageEnum.MsgDomain.MALL,
                "商城换货已完成",
                "您的售后单 " + afterSale.getAfterSaleNo() + " 换货补发已签收完成。",
                "mallAfterSale", afterSale.getAfterSaleNo(), now);
        domainEventService.recordReliableOnceAs(OpsEnum.ActorPortal.USER, userId,
                OpsEnum.EventType.MALL, "MALLAFTERSALE:" + afterSale.getAfterSaleNo(),
                MallAfterSaleServiceImpl.AUDIT_KEY_PREFIX + afterSale.getAfterSaleNo() + ":"
                        + MallEnum.AfterSaleStatus.COMPLETED.getValue(),
                "换货完成", "补发单 " + reshipment.getOrderNo() + " 已签收");
    }
}
