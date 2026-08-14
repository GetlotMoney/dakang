package com.jbk.serve.mapper.aftersale;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.tool.data.aftersale.bo.AdminAfterSaleActionBo;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.aftersale.vo.AdminAfterSaleActionItemVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;

/**
 * 售后执行动作 Mapper（E2E-04 包A）：配送取消/申诉补偿/取水异常核账共用的执行内核持久层。
 * 三条状态 CAS 的前态集合由 {@code AfterSaleTransitions.requireSources(to)} 传入，XML 内没有任何状态字面量——改矩阵即改 CAS，物理上无法漂移。
 * 可机械核验：本 Mapper 的 XML 内 grep 不到 {@code ACTION_STATUS = <数字>}。
 * 不可变列（ACTION_TYPE、SOURCE_TYPE、SOURCE_ID、ORDER_ID、CARD_ID）不进 CAS；幂等由唯一键承担（铁律②：撞 uk_after_sale_source/uk_after_sale_no 后重读核验），刻意不提供 selectCount 查重。
 *
 * @author dakang
 * @since 2026-07-29
 */
@Mapper
public interface WsAfterSaleActionMapper extends BaseMapper<WsAfterSaleAction> {

    /**
     * 按 ID 读取，绕过 {@code @TableLogic}：撞唯一键后的幂等核验与人工排查必须看得见逻辑删除行（uk_after_sale_source 不含 DATA_STATUS）。
     * 失败路径的 REQUIRES_NEW 独立事务应先调本方法重读现状再 CAS，绝不沿用已回滚事务里的对象。
     *
     * @return 含 DATA_STATUS 的行；不存在返回 null
     */
    WsAfterSaleAction selectByIdIncludingDeleted(@Param("id") Long id);

    /**
     * 按售后号读取，语义同 {@link #selectByIdIncludingDeleted}。售后号是确定性派生值（{@code AS + sha256(sourceType:sourceId)} 截断）。
     *
     * @return 含 DATA_STATUS 的行；不存在返回 null
     */
    WsAfterSaleAction selectByAfterSaleNoIncludingDeleted(@Param("afterSaleNo") String afterSaleNo);

    /**
     * 认领执行（PENDING/RETRY_WAIT → PROCESSING）。影响行必须 == 1，否则并发落败者零写入并拒绝。
     * 刻意不加 RETRY_COUNT 上限：markTerminal 落 RETRY_WAIT 时的 {@code RETRY_COUNT < maxRetryCount} 已保证末次尝试合法。
     *
     * @param expectedVersion 读到的 VERSION（乐观锁前态）
     * @param sources         合法前态集合（requireSources(toStatus)，不得为空）
     * @param now             yyyyMMddHHmmss
     * @return 影响行数
     */
    int claimForExecute(@Param("id") Long id,
                        @Param("expectedVersion") Integer expectedVersion,
                        @Param("toStatus") Integer toStatus,
                        @Param("sources") Collection<Integer> sources,
                        @Param("opUserId") Long opUserId,
                        @Param("now") String now);

    /**
     * 执行成功（PROCESSING → SUCCESS）。必须在资金写入与流水插入之后、同一事务内执行（流水撞 uk_wallet_flow_biz_key 整事务回滚，不留 SUCCESS 幽灵）。
     * 四元额度全部进 WHERE 而非只对总额兜底：总额相等而分项不同会让两维封顶对不上账；核账确认路径四个期望值均传 0。
     *
     * @param expectedVersion claim 之后的 VERSION
     * @param now             yyyyMMddHHmmss（同时写 FINISH_TIME）
     * @return 影响行数
     */
    int markSuccess(@Param("id") Long id,
                    @Param("expectedVersion") Integer expectedVersion,
                    @Param("toStatus") Integer toStatus,
                    @Param("sources") Collection<Integer> sources,
                    @Param("expectedProductFen") Long expectedProductFen,
                    @Param("expectedServiceFen") Long expectedServiceFen,
                    @Param("expectedProductMl") Long expectedProductMl,
                    @Param("expectedAmount") Long expectedAmount,
                    @Param("opUserId") Long opUserId,
                    @Param("now") String now);

    /**
     * 落终局态：RETRY_WAIT(4) / RECONCILIATION_REQUIRED(5) / TERMINATED(6)。4/5 由编排层在 catch 块经 REQUIRES_NEW 独立事务调用：
     * 主事务必须回滚（钱不能动）但失败证据必须独立提交（铁律④），前提是 claim 已在此前独立事务提交。
     * 分支由参数驱动：nextRetryTime 非空 ⇒ 可重试落点（RETRY_COUNT+1、叠加 RETRY_COUNT &lt; maxRetryCount 闸，0 行即重试耗尽改落 5）；为空 ⇒ 终态落点写 FINISH_TIME。
     * 影响 0 行不等于失败——先重读分流：已 SUCCESS 按成功返回；仍 PROCESSING 且重试耗尽改落 5。
     *
     * @param expectedVersion 独立事务内重读到的 VERSION
     * @param nextRetryTime   下次可重试时间；非空即判定为可重试落点
     * @param maxRetryCount   重试次数上限；非可重试落点传 null
     * @param lastError       失败原因（<=500 字，调用方负责截断）
     * @return 影响行数
     */
    int markTerminal(@Param("id") Long id,
                     @Param("expectedVersion") Integer expectedVersion,
                     @Param("toStatus") Integer toStatus,
                     @Param("sources") Collection<Integer> sources,
                     @Param("nextRetryTime") String nextRetryTime,
                     @Param("maxRetryCount") Integer maxRetryCount,
                     @Param("lastError") String lastError,
                     @Param("opUserId") Long opUserId,
                     @Param("now") String now);

    /**
     * R0-3/R0-7 累计封顶的已用额度：按订单聚合已成功动作的三项额度，锁定读。
     * 必须 FOR UPDATE 且调用事务 READ_COMMITTED：RR 下读视图冻结在锁卡之前，普通聚合读看不见赢家刚提交的 SUCCESS 行，同一订单会重复全额返还；
     * READ_COMMITTED 才不会在二级索引间隙互等死锁（同型修法见 {@code RechargeIssueTxImpl} 与 {@code WsOrderMapper.selectMemberDayWaterOrdersForUpdate}）。
     * 调用位置固定在 selectByIdForUpdate 锁卡之后；刻意不过滤 DATA_STATUS——删行不得成为绕过封顶的后门。
     *
     * @return 只填充 refundProductFen / refundServiceFen / refundProductMl 的载体行（恒不为 null）；严禁当成真实售后动作行传给任何 CAS
     */
    WsAfterSaleAction sumSuccessRefundByOrderForUpdate(@Param("orderId") Long orderId,
                                                       @Param("successStatus") Integer successStatus);

    /**
     * PC 售后台账分页：售后动作 LEFT JOIN 订单/用户/卡/批准人。手机号取原值别名 userPhoneRaw，由 Service 统一 PhoneMask 脱敏，明文不出接口。
     */
    IPage<AdminAfterSaleActionItemVo> pageAdminActions(Page<AdminAfterSaleActionItemVo> page,
                                                       @Param("bo") AdminAfterSaleActionBo bo);

    /** PC 售后台账详情（与分页同一列集，按售后动作ID单查）。 */
    AdminAfterSaleActionItemVo selectAdminActionById(@Param("id") Long id);

    /**
     * 补送生成后回填结果单与任务（E2E-04 包C）。RESULT_ORDER_ID IS NULL 是防重复生成的第二道物理闸
     * （另两道：确定性补送子订单号撞 uk_order_no、新订单上的 uk_dtask_order）；ACTION_TYPE=4 与 1待执行 也进 WHERE，0 行即共键错位整体回滚。
     */
    int linkResendResult(@Param("id") Long id,
                         @Param("expectedVersion") Integer expectedVersion,
                         @Param("resultOrderId") Long resultOrderId,
                         @Param("resultTaskId") Long resultTaskId,
                         @Param("opUserId") Long opUserId,
                         @Param("now") String now);

    /**
     * 补送回签成功后把动作推成 3已完成（E2E-04 包C）。RESULT_TASK_ID 进 WHERE 是要害：签收事务只能完成它自己那条补送动作；
     * 前态限定 1待执行/2执行中 且 ACTION_TYPE=4。
     */
    int markResendSucceeded(@Param("id") Long id,
                            @Param("resultTaskId") Long resultTaskId,
                            @Param("opUserId") Long opUserId,
                            @Param("now") String now);
}
