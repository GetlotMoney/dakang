package com.jbk.serve.service.settlement;

import com.jbk.tool.data.settlement.bo.GiftIssueBo;

/**
 * 运营赠卡发放服务（E2E-08 包D）。现行 D-213 口径：带有效期、不可充值（既有闸自动生效）、
 * 恒不可退批次、不占一人一卡名额；「赠卡可否充值」属外部确认项，仅留决策位。
 *
 * @author dakang
 * @since 2026-07-31
 */
public interface IGiftCardService {

    /**
     * 发放赠卡（同事务：建卡+流水+批次+审计）；请求号幂等（重放读回既有卡）。
     *
     * @return 卡 ID
     */
    Long issue(GiftIssueBo bo, Long operatorId);
}
