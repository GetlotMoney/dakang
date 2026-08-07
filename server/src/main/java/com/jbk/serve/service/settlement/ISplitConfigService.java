package com.jbk.serve.service.settlement;

import com.jbk.tool.data.settlement.bo.FinanceQueryBo;

/**
 * 分账比例配置写入（R1 P1-3：从 Controller 下沉，商品线级数据库串行锁内完成校验与写入）。
 *
 * @author dakang
 * @since 2026-08-07
 */
public interface ISplitConfigService {

    /**
     * 新增比例生效版本：锁商品线锚行 → 读同线全部版本 → 校验全部未来断点合计 ≤100% → 插入。
     * 同线写入跨实例串行化（并发双写中恰一笔成功）；不同商品线互不阻塞。
     *
     * @param bo     商品线/收款方/比例（入参合法性由调用方先验）
     * @param effect 规范化后的生效时间（yyyyMMddHHmmss，不早于当前）
     * @return 新版本 ID
     */
    Long createVersion(FinanceQueryBo bo, String effect);
}
