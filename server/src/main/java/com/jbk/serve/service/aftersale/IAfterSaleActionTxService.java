package com.jbk.serve.service.aftersale;

import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;

/**
 * 售后返还内核事务（E2E-04 包A）：只覆盖卡内权益返还（1卡内退款 / 2卡内补偿），
 * 核账/机构退款/补送各有独立路径。
 *
 * <p>四个方法分属三种事务传播（登记 REQUIRED 并入调用方事务；认领与失败落痕 REQUIRES_NEW
 * 独立提交；执行 REQUIRED + READ_COMMITTED），编排序列固定：{@code claimIndependent} 返回 true
 * 才可调 {@code executeInTx}，抛异常时在 catch 里调 {@code markTerminalIndependent}；
 * 三步不得合并成一个事务——合并即 P0#1 的病灶（见 {@link #claimIndependent}）。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface IAfterSaleActionTxService {

    /**
     * 登记一笔待执行售后动作（REQUIRED 并入调用方事务，业务回滚时一并消失）。
     * 不可伪造列（售后号/状态/版本/重试计数）由本方法钉死；幂等由唯一键收敛（铁律②），
     * 撞键读回既有行核验来源与归属，不一致即抛，不做 selectCount 预查重。
     *
     * @param draft 编排层装配好的动作行（不含主键）
     * @param now   业务时间（yyyyMMddHHmmss，与调用方业务写入同源）
     * @return 落库后的动作行（含主键）；幂等命中时为库中既有行
     */
    WsAfterSaleAction createPending(WsAfterSaleAction draft, String now);

    /**
     * 认领执行（1待执行/4可重试 → 2执行中），REQUIRES_NEW 独立提交。
     * P0：认领与资金写入同事务时，资金失败回滚会把状态复原成待执行，落痕 CAS（前态=执行中）恒 0 行，
     * 失败动作无终态、无 LAST_ERROR 且可被无限重放。
     *
     * @param id              售后动作ID
     * @param expectedVersion 编排层读到的 VERSION（乐观锁前态）
     * @param opUserId        操作人ID
     * @param now             yyyyMMddHHmmss
     * @return true 认领成功；false 已被他人认领或状态/版本已变（编排层据此<b>放弃本次执行</b>，
     *         不得继续调用 {@link #executeInTx}）
     */
    boolean claimIndependent(Long id, Integer expectedVersion, Long opUserId, String now);

    /**
     * 执行卡内权益返还：锁卡 → 封顶判定 → 原子返还 → 唯一流水 → 标记成功 → 同事务审计。
     * 入口断言 ACTION_STATUS=2执行中、不再认领；任一步失败整体回滚（钱不动），由编排层独立落痕。
     *
     * @param id       售后动作ID（已处于 2执行中）
     * @param opUserId 操作人ID
     * @param now      yyyyMMddHHmmss（卡、流水、状态、审计同源）
     */
    void executeInTx(Long id, Long opUserId, String now);

    /**
     * 落失败/终局态（4可重试 / 5需人工对账 / 6已终止），REQUIRES_NEW 独立提交（铁律④后半句：
     * 主事务回滚保「钱没动」，失败证据必须独立存活）。调用方必须保证
     * 「toStatus=4可重试 ⟺ nextRetryTime 非空」，缺一即退化为无上限重放。
     *
     * @param id              售后动作ID
     * @param expectedVersion 编排层持有的 VERSION（本方法内会在 CAS 落空时重读现状，不沿用它做二次判定）
     * @param toStatus        目标态（{@code AfterSaleEnum.ActionStatus} 的 4/5/6）
     * @param nextRetryTime   下次可重试时间；非空即判定为可重试落点
     * @param lastError       失败原因（超长由实现截断到 500）
     * @param opUserId        操作人ID
     * @param now             yyyyMMddHHmmss
     */
    void markTerminalIndependent(Long id, Integer expectedVersion, int toStatus,
                                 String nextRetryTime, String lastError, Long opUserId, String now);
}
