package com.jbk.serve.controller.mall;

import com.jbk.tool.annotation.RepeatSubmit;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 库存动作入口合同。
 *
 * <p>adjust 的 requestId 由数据库唯一键提供跨进程、跨时间的业务幂等；若叠加短时
 * {@link RepeatSubmit}，合法网络重试会在进入领域幂等核验前被拒绝，无法返回原动作冻结结果。</p>
 */
class MallStockControllerContractTest {

    @Test
    void durableIdempotentAdjustMustNotBeBlockedByShortWindowRepeatGuard() throws Exception {
        Method adjust = MallStockController.class.getMethod(
            "adjust",
            com.jbk.tool.data.mall.bo.MallStockAdjustBo.class
        );
        assertNull(adjust.getAnnotation(RepeatSubmit.class));
    }
}
