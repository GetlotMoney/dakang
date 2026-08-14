package com.jbk.serve.service.settlement.impl;

import com.jbk.serve.mapper.settlement.WsSplitConfigMapper;
import com.jbk.serve.mapper.settlement.WsSplitLineLockMapper;
import com.jbk.tool.data.settlement.bo.FinanceQueryBo;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V1 比例配置收款方白名单（D-428 附带整改）。此前字典 1377 的预留值 4/5/6 在页面
 * 选得到、后端照收、天花板校验照算，而执行面永远不为它们产行——配了显示"已生效"，
 * 份额静默落平台且账面无痕。本类钉住：白名单外的组合在<b>任何写入发生前</b>被拒。
 */
@DisplayName("V1 比例配置收款方白名单")
class SplitConfigWhitelistTest {

    private final WsSplitConfigMapper configMapper = Mockito.mock(WsSplitConfigMapper.class);
    private final WsSplitLineLockMapper lockMapper = Mockito.mock(WsSplitLineLockMapper.class);
    private final SplitConfigServiceImpl service = new SplitConfigServiceImpl(configMapper, lockMapper);

    private static FinanceQueryBo bo(int line, int receiver, int rate) {
        return new FinanceQueryBo().setProductLine(line).setReceiverType(receiver).setSplitRate(rate);
    }

    @ParameterizedTest(name = "线{0} 收款方{1} 必须被拒")
    @CsvSource({
            "1,2", // 售水线配配送员：执行面只在配送线消费配送员
            "1,3", "2,3", // 平台是余数不是比例
            "1,4", "1,5", "1,6", // 渠道/推荐人/区域服务商：预留值，引擎不消费
            "2,4", "2,5", "2,6"
    })
    @DisplayName("白名单外组合在任何写入前被拒（反向验证点：删白名单本测必红）")
    void nonConsumableReceiverRejectedBeforeAnyWrite(int line, int receiver) {
        JbkException e = assertThrows(JbkException.class,
                () -> service.createVersion(bo(line, receiver, 500), "20990101000000"));
        assertTrue(e.getMsg().contains("不参与") || e.getMsg().contains("余数"), "实际=" + e.getMsg());
        // 拒绝必须发生在锁锚之前：白名单是入口判据，不消耗任何数据库资源
        verify(lockMapper, never()).lockLine(anyInt());
        verify(configMapper, never())
                .insert(any(com.jbk.tool.data.settlement.po.WsSplitConfig.class));
    }

    @Test
    @DisplayName("白名单内组合（配送线配送员）正常走到锁锚")
    void consumableReceiverProceedsToLock() {
        when(lockMapper.lockLine(2)).thenReturn(1);
        when(configMapper.selectList(any())).thenReturn(List.of());
        when(configMapper.insert(any(com.jbk.tool.data.settlement.po.WsSplitConfig.class)))
                .thenAnswer(inv -> {
                    inv.getArgument(0, com.jbk.tool.data.settlement.po.WsSplitConfig.class).setId(77L);
                    return 1;
                });
        Long id = service.createVersion(bo(2, 2, 3000), "20990101000000");
        assertNotNull(id);
        verify(lockMapper).lockLine(2);
    }
}
