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
 * 分账比例配置写入（R1 P1-3）。
 *
 * <h3>为什么必须锁在数据库层</h3>
 * <p>「读旧配置→内存断点校验→INSERT」不串行化时，两个请求改<b>不同收款方</b>互不撞
 * {@code uk_split_config_version}——各自按旧视图校验通过后双双写入，同线未来断点合计
 * 可超 100%，到点后所有配送签收在分账引擎 fail-closed，配送完成链熔断（独立复审实测
 * 9000/8000 双过→合计 11000）。修复=同一事务内：锁商品线锚行（{@code FOR UPDATE}，
 * 跨实例生效）→ 锁内重读全部版本 → 校验全部未来断点 → INSERT。同线串行、异线并发。</p>
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
}
