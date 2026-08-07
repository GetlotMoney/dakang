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
 * 售后执行动作 Mapper（E2E-04 包A）——三条来源（配送取消 / 配送申诉补偿 / 取水异常核账）共用的执行内核持久层。
 *
 * <p><b>状态机是 CAS 的数据源，不是它的旁证。</b>本 Mapper 的三条状态 CAS
 * （{@link #claimForExecute} / {@link #markSuccess} / {@link #markTerminal}）一律由调用方传入
 * {@code AfterSaleTransitions.requireSources(to)} 返回的合法前态集合，XML 用 {@code <foreach>} 渲染
 * {@code ACTION_STATUS IN (...)}；<b>XML 里没有任何状态字面量</b>（目标态同样由 {@code toStatus} 传入）。
 * 反面教材是既有的 {@code DeliveryTransitions.allowed()}：它只被单测引用，生产路径另走一套硬编码 WHERE，
 * 于是状态机有两份真相、矩阵单测保护不到生产。此处不重蹈覆辙——改矩阵即改 CAS，物理上无法漂移。
 * 可机械核验：本 Mapper 的 XML 内 grep 不到 {@code ACTION_STATUS = <数字>}。</p>
 *
 * <p><b>不可变列不进 CAS</b>：{@code ACTION_TYPE / SOURCE_TYPE / SOURCE_ID / ORDER_ID / CARD_ID} 建行后
 * 没有任何 SQL 会改写，不存在并发漂移，准入判定（哪些动作类型属本包可执行）放在 Java 侧的
 * {@code AfterSaleEnum.ActionType} 单一出处即可；把它们塞进 WHERE 只会让「影响行 0」的归因变模糊。
 * 会被并发改写的只有 {@code ACTION_STATUS / VERSION / RETRY_COUNT}，它们才是 CAS 的前态条件。</p>
 *
 * <p><b>幂等由库层唯一键承担</b>（铁律②）：新建走 {@code insert(po)}，撞 {@code uk_after_sale_source}
 * 或 {@code uk_after_sale_no} 抛 {@code DuplicateKeyException}，由编排层重读该行核验归属后返回幂等结果。
 * 本 Mapper <b>刻意不提供任何 selectCount 查重方法</b>——应用层查重不是幂等手段。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Mapper
public interface WsAfterSaleActionMapper extends BaseMapper<WsAfterSaleAction> {

    /**
     * 按 ID 读取，<b>绕过 {@code @TableLogic}</b>（含 {@code DATA_STATUS=1} 的行）。
     *
     * <p>两个场景必须读到逻辑删除行：① 撞唯一键后的幂等核验——{@code uk_after_sale_source} 不含
     * {@code DATA_STATUS}（删了再建绕不过唯一约束重复返还），若用 MP 的 {@code selectById} 会「键被占用但查不到行」，
     * 编排层只能抛一个无法归因的异常；② 落 5需人工对账 后的排查，运营必须看得见那一行。</p>
     *
     * <p>失败路径的 REQUIRES_NEW 独立事务（记 RETRY_WAIT / RECONCILIATION_REQUIRED）应先调本方法
     * 重读现状再做 CAS：绝不沿用已回滚事务里的对象（照 {@code RechargeCreditFailureTxImpl} 的做法），
     * 那个对象的 VERSION 与状态在库里已经不成立。</p>
     *
     * @param id 售后动作ID
     * @return 含 DATA_STATUS 的行；不存在返回 null
     */
    WsAfterSaleAction selectByIdIncludingDeleted(@Param("id") Long id);

    /**
     * 按售后号读取，语义同 {@link #selectByIdIncludingDeleted}（绕过 {@code @TableLogic}）。
     * <p>售后号是确定性派生值（{@code AS + sha256(sourceType:sourceId)} 截断），PC 与小程序的入参都用它，
     * 因此幂等命中后的归属核验走这条路。</p>
     *
     * @param afterSaleNo 售后号
     * @return 含 DATA_STATUS 的行；不存在返回 null
     */
    WsAfterSaleAction selectByAfterSaleNoIncludingDeleted(@Param("afterSaleNo") String afterSaleNo);

    /**
     * 认领执行（PENDING/RETRY_WAIT → PROCESSING）。影响行必须 {@code == 1}，否则并发落败者零写入并拒绝。
     *
     * <p>前态集合由 {@code AfterSaleTransitions.requireSources(toStatus)} 提供，本方法不认识任何状态数字。
     * 非状态条件留在 SQL：{@code NEXT_RETRY_TIME IS NULL OR <= now}（PENDING 行该列恒为 NULL 天然放行，
     * RETRY_WAIT 行必须到点）。<b>刻意不加 RETRY_COUNT 上限</b>：markTerminal 落 RETRY_WAIT 时的
     * {@code RETRY_COUNT < maxRetryCount} 保证计数最多加到上限值，最后一次认领是合法的末次尝试；
     * 它再失败时 markTerminal(4) 影响 0 行，编排层据此升级为 5需人工对账。</p>
     *
     * @param id              售后动作ID
     * @param expectedVersion 读到的 VERSION（乐观锁前态）
     * @param toStatus        目标态 PROCESSING（由 {@code AfterSaleEnum.ActionStatus} 提供，不在 XML 硬编码）
     * @param sources         合法前态集合（{@code AfterSaleTransitions.requireSources(toStatus)}，不得为空）
     * @param opUserId        操作人ID（UPDATE_BY）
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
     * 执行成功（PROCESSING → SUCCESS）。影响行必须 {@code == 1}；必须在资金写入与流水插入<b>之后</b>、同一事务内执行
     * （流水撞 {@code uk_wallet_flow_biz_key} 会整事务回滚，不留 SUCCESS 幽灵）。
     *
     * <p>四元额度在 WHERE 里二次钉死：创建时算定的
     * {@code REFUND_PRODUCT_FEN / REFUND_SERVICE_FEN / REFUND_PRODUCT_ML / REFUND_AMOUNT}
     * 在执行期不得漂移。四列都写进 WHERE 而不是只对总额兜底——总额相等而分项不同的行，
     * 会让「水品/配送费」两维封顶的账对不上（本表分列的全部意义就在这里）。
     * 核账确认路径（零资金变化）四个期望值均传 0。</p>
     *
     * @param id                售后动作ID
     * @param expectedVersion   claim 之后的 VERSION
     * @param toStatus          目标态 SUCCESS
     * @param sources           合法前态集合（requireSources(SUCCESS)）
     * @param expectedProductFen 期望的水品返还金额(分)
     * @param expectedServiceFen 期望的配送费返还金额(分)
     * @param expectedProductMl  期望的水品返还水量(毫升)
     * @param expectedAmount     期望的返还金额合计(分)
     * @param opUserId          操作人ID
     * @param now               yyyyMMddHHmmss（同时写 FINISH_TIME）
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
     * 落终局态：RETRY_WAIT(4，可重试) / RECONCILIATION_REQUIRED(5，需人工对账) / TERMINATED(6，已终止)。
     *
     * <p><b>调用位置</b>：4/5 由编排层在 catch 块中经 REQUIRES_NEW 独立事务调用——主事务必须回滚（钱不能动），
     * 但失败证据必须独立提交存活（铁律④）。前提是 claim 已在<b>此前的独立事务</b>里提交，
     * 否则主事务回滚会把 ACTION_STATUS 一并复原成 PENDING，这里的 CAS 必然影响 0 行、证据全丢。</p>
     *
     * <p><b>分支由参数驱动，不由状态数字驱动</b>（XML 里没有状态字面量）：
     * 传 {@code nextRetryTime != null} ⇒ 可重试落点，{@code RETRY_COUNT+1} 且不写 FINISH_TIME，
     * 并叠加 {@code RETRY_COUNT < maxRetryCount} 闸（影响 0 行即重试耗尽，编排层改调本方法落 5）；
     * 传 {@code nextRetryTime == null} ⇒ 终态落点，写 FINISH_TIME、不动 RETRY_COUNT。
     * 调用方必须保证「toStatus=RETRY_WAIT ⟺ nextRetryTime 非空」。</p>
     *
     * <p>VERSION 同样是前态条件：独立事务里先 {@link #selectByIdIncludingDeleted} 重读再传入。
     * 影响 0 行不等于失败——先重读分流：已 SUCCESS 说明主事务其实提交成功（COMMIT 结果不确定场景），
     * 按成功返回；仍在 PROCESSING 且重试耗尽则改落 5；其余记独立审计后返回。</p>
     *
     * @param id              售后动作ID
     * @param expectedVersion 独立事务内重读到的 VERSION
     * @param toStatus        目标态（4/5/6，由 {@code AfterSaleEnum.ActionStatus} 提供）
     * @param sources         合法前态集合（requireSources(toStatus)）
     * @param nextRetryTime   下次可重试时间；非空即判定为可重试落点
     * @param maxRetryCount   重试次数上限（单一出处在 Java 常量，SQL 不写魔数）；非可重试落点传 null
     * @param lastError       失败原因（<=500 字，调用方负责截断）
     * @param opUserId        操作人ID
     * @param now             yyyyMMddHHmmss
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
     * R0-3/R0-7 累计封顶的已用额度：按订单聚合<b>已成功</b>动作的四元额度中的三项，<b>锁定读</b>。
     *
     * <p><b>为什么必须是锁定读 + READ_COMMITTED</b>：MySQL 默认 REPEATABLE READ 下，一致性读视图在事务的
     * 第一条普通 SELECT 时就固定；返还事务必然先普通读订单快照与原扣款流水，读视图因此冻结在<b>锁卡之前</b>。
     * 后到的第二笔售后在卡行锁上醒来后，普通聚合读仍走旧视图、看不见赢家刚提交的 SUCCESS 行，
     * 于是 used=0、封顶放行 —— 同一订单可重复全额返还，而 uk_after_sale_source（不同 appealId）与
     * uk_wallet_flow_biz_key（不同 afterSaleNo）都拦不住。本仓已两次踩同一个坑并留有权威修法：
     * {@code RechargeIssueTxImpl}（改 READ_COMMITTED）与 {@code WsOrderMapper.selectMemberDayWaterOrdersForUpdate}
     * （改 FOR UPDATE）。</p>
     *
     * <p>两道措施缺一不可：① 本查询 {@code FOR UPDATE} 是当前读，直接读最新已提交版本；
     * ② 调用事务必须标 {@code @Transactional(isolation = READ_COMMITTED)}——READ_COMMITTED 下 InnoDB 不加 gap 锁，
     * 本查询走二级索引 {@code idx_after_sale_order} 的范围锁定读才不会锁住相邻 ORDER_ID 的空隙
     * （RR 下两笔无关订单的并发售后会在同一间隙上插入意向互等死锁，同型教训见 RechargeIssueTxImpl 的注释）。</p>
     *
     * <p><b>调用位置固定</b>：必须在 {@code selectByIdForUpdate} 锁卡<b>之后</b>调用。锁卡把同一张卡的返还串行化，
     * 醒来后的这次当前读才必然看得见赢家的 SUCCESS 行；放在锁卡之前，即使 FOR UPDATE 也只是把竞态提前。</p>
     *
     * <p><b>刻意不过滤 DATA_STATUS</b>：逻辑删除的成功动作，钱早已真实打进卡里；过滤它等于让「删行」
     * 成为绕过封顶的后门（与 uk_after_sale_source 不带 DATA_STATUS 是同一条理由）。</p>
     *
     * @param orderId       订单ID（额度聚合锚点）
     * @param successStatus SUCCESS 状态值（由 {@code AfterSaleEnum.ActionStatus} 提供，SQL 不写状态字面量）
     * @return 只填充 refundProductFen / refundServiceFen / refundProductMl 三列的载体行（无成功动作时三项均为 0，
     *         恒不为 null）；其余字段一律为 null，<b>严禁把它当成一条真实的售后动作行传给任何 CAS</b>
     */
    WsAfterSaleAction sumSuccessRefundByOrderForUpdate(@Param("orderId") Long orderId,
                                                       @Param("successStatus") Integer successStatus);

    /**
     * PC 售后台账分页：售后动作为主表，LEFT JOIN 订单/用户/卡/批准人派生展示列。
     * <p>手机号取原值别名 {@code userPhoneRaw}，由 Service 层统一 {@code PhoneMask} 脱敏后下发
     * （Vo 上 {@code @JsonIgnore} 兜底），明文不出接口。</p>
     * <p>Bo 需提供的筛选字段：{@code actionStatus}、{@code sourceType}、{@code actionType}、
     * {@code keyword}（售后号/订单号/用户姓名/手机号模糊）。</p>
     */
    IPage<AdminAfterSaleActionItemVo> pageAdminActions(Page<AdminAfterSaleActionItemVo> page,
                                                       @Param("bo") AdminAfterSaleActionBo bo);

    /** PC 售后台账详情（与分页同一列集，按售后动作ID单查）。 */
    AdminAfterSaleActionItemVo selectAdminActionById(@Param("id") Long id);

    /**
     * 补送生成后回填结果单与任务（E2E-04 包C）。
     *
     * <p>{@code RESULT_ORDER_ID IS NULL} 是防重复生成的第二道物理闸：
     * 第一道是补送子订单号由 {@code RESEND:APPEAL:<appealId>} 确定性派生、
     * 撞 {@code uk_order_no}；第三道是新订单上的 {@code uk_dtask_order} 保证一单一任务。
     * 三道各自独立，任何一道单独失效都不会导致重复补送。</p>
     *
     * <p>{@code ACTION_TYPE = 4} 与 {@code ACTION_STATUS = 1待执行} 也进 WHERE：
     * 补送结果只能挂在一条待执行的补送动作上，挂到别的类型或已终态的动作上
     * 意味着共键错位，此时影响 0 行、调用方整体回滚。</p>
     */
    int linkResendResult(@Param("id") Long id,
                         @Param("expectedVersion") Integer expectedVersion,
                         @Param("resultOrderId") Long resultOrderId,
                         @Param("resultTaskId") Long resultTaskId,
                         @Param("opUserId") Long opUserId,
                         @Param("now") String now);

    /**
     * 补送回签成功后把动作推成 3已完成（E2E-04 包C）。
     *
     * <p>{@code RESULT_TASK_ID = #{resultTaskId}} 进 WHERE 是本方法的要害：
     * 签收事务只能完成<b>它自己那条</b>补送任务对应的动作。少了这条谓词，
     * 任意一次签收都能把某条补送动作标成完成——那正是「重复签收或共键错位
     * 提前显示补送完成」的形状。</p>
     *
     * <p>前态限定 1待执行/2执行中，且 ACTION_TYPE=4：已终态的动作不得被再次推进。</p>
     */
    int markResendSucceeded(@Param("id") Long id,
                            @Param("resultTaskId") Long resultTaskId,
                            @Param("opUserId") Long opUserId,
                            @Param("now") String now);
}
