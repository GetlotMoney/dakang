package com.jbk.serve.service.settlement;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.settlement.po.WsIncomeAccount;

/**
 * 收益账户服务（E2E-08 / REQ-072）：分润余额与水卡余额/用户余额物理隔离。
 * 余额变动一律「前值+VERSION」双条件 CAS + 收益流水同事务落行（幂等键唯一）。
 *
 * @author dakang
 * @since 2026-07-31
 */
public interface IIncomeService extends IService<WsIncomeAccount> {

    /**
     * 分润入账（Worker 推进 1→2 后调用，与状态推进同事务）：
     * 无账户则建户（uk_income_account_user 撞键读回），CAS 加余额，
     * 流水幂等键 INCOME:&lt;splitId&gt;——撞键说明该分账已入过账，整体静默幂等。
     *
     * @return 是否真正发生入账（false=幂等重放）
     */
    boolean creditFromSplit(Long splitId, Long userId, long amountFen, String orderNo);

    /**
     * 本人钱包视图（铁律6：只认会话 userId）：无账户=零余额空流水（不建户——
     * 查看不产生数据）；流水近 50 条倒序，行内不含任何下级用户身份字段（REQ-072 脱敏）。
     */
    com.jbk.tool.data.mini.vo.MiniWalletVo walletFor(Long userId);

    /**
     * 提现申请骨架（REQ-061/072：提现必须后台审核，本期不做真实出金）：
     * 可用余额 CAS 转入冻结 + 流水(3提现冻结) + 审计，请求号幂等 WITHDRAW:&lt;requestId&gt;。
     */
    void applyWithdraw(Long userId, long amountFen, String requestId);

    /**
     * 提现驳回（PC 审核骨架）：冻结 CAS 回可用 + 流水(5驳回解冻) + 审计。
     * 审核通过（真实出金）属外部能力，本期不提供通过路径。
     */
    void rejectWithdraw(Long userId, long amountFen, String requestId, Long operatorId);
}
