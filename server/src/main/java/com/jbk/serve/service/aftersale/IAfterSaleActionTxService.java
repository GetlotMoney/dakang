package com.jbk.serve.service.aftersale;

import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;

/**
 * 售后返还内核事务（E2E-04 包A）——三条来源（待接单取消 / 配送申诉补偿 / 取水异常核账）
 * 共用的执行事务边界。
 *
 * <p><b>本接口的四个方法分属三种事务传播，是一条铁律而不是实现细节</b>：</p>
 * <ol>
 *   <li>{@link #createPending} REQUIRED：由裁决/取消编排层在<b>它们自己的事务内</b>调用，
 *       与申诉裁决、订单撤单等业务写入同生共死。业务回滚时这条待执行动作必须一并消失，
 *       否则会留下一笔「业务没发生、售后却待执行」的幽灵返还。</li>
 *   <li>{@link #claimIndependent} 与 {@link #markTerminalIndependent} REQUIRES_NEW：
 *       认领与失败证据都必须<b>独立提交</b>，理由见各自方法注释。</li>
 *   <li>{@link #executeInTx} REQUIRED + READ_COMMITTED：资金写入本体。</li>
 * </ol>
 *
 * <p><b>编排层的调用序列是固定的</b>：{@code claimIndependent} 返回 true 才可以调
 * {@code executeInTx}；{@code executeInTx} 抛异常时在 catch 块里调
 * {@code markTerminalIndependent}。三步<b>不得合并成一个事务</b>——合并即是 P0#1 的病灶
 * （见 {@link #claimIndependent}）。</p>
 *
 * <p><b>本接口只覆盖卡内权益返还（1卡内退款 / 2卡内补偿）。</b>取水异常核账是零资金写入的
 * 核账确认，走独立事务实现，那个实现<b>不注入 {@code TradeCardMapper}</b>——用编译期依赖
 * 而不是运行期 if 杜绝「核账走上加钱路径」；本实现反过来在入口拒绝核账来源。
 * 机构退款（包B）与补送（包C）同理不在本接口射程内。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface IAfterSaleActionTxService {

    /**
     * 登记一笔待执行售后动作（{@code REQUIRED}：并入调用方的裁决/取消事务）。
     *
     * <p>不可伪造的列由本方法钉死而不是信任 draft：{@code AFTER_SALE_NO} 按来源确定性派生、
     * {@code ACTION_STATUS=1待执行}、{@code VERSION=1}、{@code RETRY_COUNT=0}，
     * 以及终态相关列一律置空。编排层只负责业务字段（来源、归属、四元额度、策略与快照）。</p>
     *
     * <p><b>幂等由库层唯一键收敛（铁律②）</b>：同来源重放撞 {@code uk_after_sale_source}
     * 或 {@code uk_after_sale_no} 时读回既有行核验来源与归属，一致即幂等返回既有行；
     * 不一致抛出——同一个来源不可能有两种售后诉求，那是数据事故而不是重放。
     * 本方法<b>不做任何 selectCount 预查重</b>：预查重不是幂等手段。</p>
     *
     * @param draft 编排层装配好的动作行（不含主键）
     * @param now   业务时间（yyyyMMddHHmmss，与调用方业务写入同源）
     * @return 落库后的动作行（含主键）；幂等命中时为库中既有行
     */
    WsAfterSaleAction createPending(WsAfterSaleAction draft, String now);

    /**
     * 认领执行（1待执行/4可重试 → 2执行中），<b>{@code REQUIRES_NEW} 独立提交</b>。
     *
     * <p><b>P0：认领绝不能与资金写入同事务。</b>若二者同事务，资金写入失败时主事务回滚会把
     * {@code ACTION_STATUS} 一并复原成 1待执行，而
     * {@link #markTerminalIndependent} 的 CAS 要求前态是 2执行中 → 影响行恒为 0 →
     * 失败动作永远停在待执行、无终态、无 {@code LAST_ERROR}、PC 上看不到任何失败痕迹，
     * 且因为它一直是「待执行」而可被 Worker 无限重放。独立提交后，认领这一事实先于资金结果落库，
     * 失败证据才有落点。</p>
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
     *
     * <p>入口断言 {@code ACTION_STATUS=2执行中}：认领已由 {@link #claimIndependent} 独立提交，
     * 本方法<b>不再认领</b>。任一步失败整体回滚（钱不动、流水不留、状态不前进），
     * 由编排层在 catch 里调 {@link #markTerminalIndependent} 独立落痕。</p>
     *
     * @param id       售后动作ID（已处于 2执行中）
     * @param opUserId 操作人ID
     * @param now      yyyyMMddHHmmss（卡、流水、状态、审计同源）
     */
    void executeInTx(Long id, Long opUserId, String now);

    /**
     * 落失败/终局态（4可重试 / 5需人工对账 / 6已终止），<b>{@code REQUIRES_NEW} 独立提交</b>。
     *
     * <p>铁律④后半句：主事务必须回滚（钱不能动），但失败证据必须独立存活——
     * 与主事务同生共死的话，回滚会把 {@code LAST_ERROR} 与终态一起抹掉，
     * 失败就成了无痕事件，运营既查不到原因也不知道该不该人工介入。</p>
     *
     * <p>调用方必须保证「{@code toStatus=4可重试} ⟺ {@code nextRetryTime} 非空」：
     * 重试排期与重试计数递增在 SQL 里同源，缺一即退化为无上限重放。</p>
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
