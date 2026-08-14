package com.jbk.serve.service.settlement.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.settlement.WsSplitConfigMapper;
import com.jbk.serve.mapper.settlement.WsSplitLineLockMapper;
import com.jbk.serve.service.settlement.ISplitConfigService;
import com.jbk.tool.consts.settlement.SettlementEnum;
import com.jbk.tool.data.settlement.bo.FinanceQueryBo;
import com.jbk.tool.data.settlement.po.WsSplitConfig;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 分账比例配置写入（R1 P1-3）。断点校验必须锁在数据库层：改不同收款方互不撞
 * uk_split_config_version，各按旧视图校验会让同线未来断点合计超 100%、到点分账引擎熔断；
 * 同一事务内锁商品线锚行（FOR UPDATE）→ 锁内重读全部版本 → 校验 → INSERT，同线串行异线并发。
 *
 * @author dakang
 * @since 2026-08-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SplitConfigServiceImpl implements ISplitConfigService {

    private final WsSplitConfigMapper configMapper;
    private final WsSplitLineLockMapper lineLockMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createVersion(FinanceQueryBo bo, String effect) {
        // ⓪ 收款方白名单：只放行 V1 引擎执行面真正消费的组合（SplitServiceImpl 的收款方
        //    集合硬编码为 机主/配送员/平台）。字典 1377 的 4/5/6 预留值页面选得到、
        //    本方法此前也照收——配了显示"已生效"、占用 100% 额度，引擎却永远不产行，
        //    份额静默落平台且账面无痕（V2 立项点名的偏差 3）。平台是余数不是比例，同拒。
        requireConsumableReceiver(bo);
        // ① 商品线锁锚：拿不到锚行=新库未执行 split-line-lock 迁移，fail-closed 拒绝写入，
        //    绝不退化为无锁校验（那正是被驳回的窗口本身）
        Integer locked = lineLockMapper.lockLine(bo.getProductLine());
        if (ObjectUtil.isNull(locked)) {
            throw new JbkException("商品线锁锚未初始化（请先执行 2026-08-07-split-line-lock 迁移），拒绝写入");
        }

        // ② 锁内重读同线全部非平台版本：此刻并发写入者要么已提交（可见）要么在锚行上排队
        List<WsSplitConfig> lineConfigs = configMapper.selectList(
                Wrappers.lambdaQuery(WsSplitConfig.class)
                        .eq(WsSplitConfig::getProductLine, bo.getProductLine())
                        .ne(WsSplitConfig::getReceiverType, SettlementEnum.ReceiverType.PLATFORM.getValue())
                        .orderByAsc(WsSplitConfig::getEffectTime));

        // ③ 全未来断点校验：对「本行生效时点起」的每个断点，取各收款方届时现行版本
        //    （含本次新行及其被同收款方更晚版本覆盖的关系）验合计 ≤100%
        if (bo.getReceiverType() != SettlementEnum.ReceiverType.PLATFORM.getValue()) {
            TreeSet<String> breakpoints = new TreeSet<>();
            breakpoints.add(effect);
            for (WsSplitConfig c : lineConfigs) {
                if (c.getEffectTime().compareTo(effect) > 0) {
                    breakpoints.add(c.getEffectTime());
                }
            }
            for (String at : breakpoints) {
                Map<Integer, Integer> currentByReceiver = new HashMap<>();
                for (WsSplitConfig c : lineConfigs) {
                    if (c.getEffectTime().compareTo(at) <= 0) {
                        currentByReceiver.put(c.getReceiverType(), c.getSplitRate());
                    }
                }
                currentByReceiver.put(bo.getReceiverType(), bo.getSplitRate());
                for (WsSplitConfig c : lineConfigs) {
                    if (c.getReceiverType() == bo.getReceiverType()
                            && c.getEffectTime().compareTo(effect) > 0
                            && c.getEffectTime().compareTo(at) <= 0) {
                        currentByReceiver.put(c.getReceiverType(), c.getSplitRate());
                    }
                }
                long sum = currentByReceiver.values().stream().mapToLong(Integer::longValue).sum();
                if (sum > 10_000) {
                    throw new JbkException("同商品线各收款方生效比例合计将在 " + at + " 起超出 100%（合计 "
                            + sum + " 万分比），拒绝写入");
                }
            }
        }

        // ④ 插入（影响行数校验；同键并发由唯一键兜底转文案）
        WsSplitConfig config = new WsSplitConfig()
                .setProductLine(bo.getProductLine())
                .setReceiverType(bo.getReceiverType())
                .setSplitRate(bo.getSplitRate())
                .setEffectTime(effect)
                .setConfigRemark(StrUtil.brief(bo.getRemark(), 200));
        try {
            if (configMapper.insert(config) != 1 || config.getId() == null) {
                throw new JbkException("比例配置写入失败");
            }
        } catch (DuplicateKeyException e) {
            throw new JbkException("同线同收款方同生效时点已有版本，请调整生效时间");
        }
        log.info("分账比例新版本写入：line={} receiver={} rate={} effect={} id={}",
                bo.getProductLine(), bo.getReceiverType(), bo.getSplitRate(), effect, config.getId());
        return config.getId();
    }

    /**
     * V1 可配收款方白名单：售水线只有机主，配送线只有机主与配送员——与
     * {@code SplitServiceImpl.enqueueForOrder} 的执行面逐值同源。推荐人/区域服务商
     * 的比例属 V2 整版计划（/finance/plan/create），不允许配进 V1 单行版本。
     */
    private static void requireConsumableReceiver(FinanceQueryBo bo) {
        if (ObjectUtil.equal(bo.getReceiverType(), SettlementEnum.ReceiverType.PLATFORM.getValue())) {
            throw new JbkException("平台份额恒为余数，不可配置比例");
        }
        boolean owner = ObjectUtil.equal(bo.getReceiverType(), SettlementEnum.ReceiverType.OWNER.getValue());
        boolean courierOnDelivery = ObjectUtil.equal(bo.getReceiverType(),
                SettlementEnum.ReceiverType.COURIER.getValue())
                && ObjectUtil.equal(bo.getProductLine(), SettlementEnum.ProductLine.DELIVERY.getValue());
        if (!owner && !courierOnDelivery) {
            throw new JbkException("该收款方不参与此商品线的单行比例分账；"
                    + "推荐人与区域服务商比例请在分润计划（整版）中配置");
        }
    }
}
