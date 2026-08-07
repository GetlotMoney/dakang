package com.jbk.serve.mapper.trade;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.user.po.WsCard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 交易域水卡原子扣减 Mapper（trade 自持，不改 user 模块 WsCardService；契约③共存约定）。
 * <p>资金铁律1：扣减必须 UPDATE ... WHERE 余量>=X 并校验影响行数，禁止读出→内存计算→写回。
 * 继承 BaseMapper 提供 selectById 供扣减后 AFTER 快照与失败原因定位。</p>
 *
 * <p>CARD-MEMBER：{@code ws_card.USER_ID} 恒为卡主。扣减/补偿的卡归属条件由
 * {@code expectedOwnerUserId} 承担（调用方在锁内读出卡主后<b>显式传入</b>，绝不能简单删除该条件——
 * 删掉它等于放弃「扣的确实是这张卡主的卡」这道库层防线）；{@code UPDATE_BY} 写实际操作人
 * （成员取水时为成员）；启用 CARD-MEMBER 后，两者不再是同一个语义。</p>
 *
 * <p><b>三条加法 SQL 的 WHERE 口径互不相同，绝不是同义词</b>（不写清楚，后来者必然挑一个顺手的用）：</p>
 * <table border="1">
 *   <caption>加法类 CAS 的前态条件对照</caption>
 *   <tr><th>方法</th><th>卡状态条件</th><th>余额/水量前态</th><th>写入列</th><th>适用范围</th></tr>
 *   <tr><td>{@link #refundCardAssets}</td><td>{@code CARD_STATUS IN (1,2,3)}</td>
 *       <td>双列全前态（{@code BALANCE_AMOUNT} + {@code BALANCE_ML}）</td><td>金额与水量一次写入</td>
 *       <td><b>新代码唯一合法入口</b>（E2E-04 售后返还）</td></tr>
 *   <tr><td>{@link #compensateBalance}</td><td><b>不校验</b>（注销卡也能入账）</td><td>无</td><td>仅金额</td>
 *       <td>仅 E2E-01 取水退差历史路径</td></tr>
 *   <tr><td>{@link #compensateMl}</td><td><b>不校验</b>（注销卡也能入账）</td><td>无</td><td>仅水量</td>
 *       <td>仅 E2E-01 取水退差历史路径</td></tr>
 * </table>
 *
 * <p><b>迁移待办（登记在此，勿再新增调用点）</b>：{@code compensateBalance} / {@code compensateMl}
 * 现存调用点恰为 {@code TradeOrderTxServiceImpl.settleWaterOrder} 的两个补偿分支（E2E-01 封板事务）。
 * <p><b>迁移闸门（2026-08-03 更新）</b>：{@code compensateBalance}/{@code compensateMl} 迁到
 * {@code refundCardAssets} 的前置条件——「settleWaterOrder 先锁卡」——<b>已经满足</b>：
 * 该方法入口现在无条件调用 {@code selectByIdForUpdate}（锁序对齐下单事务的「卡 → 订单」，
 * 由 TradeOrderTxServiceImplTest 的两条锁序不变式用例看守）。
 * 剩余闸门是口径差异本身：本组补偿 SQL 不带 CARD_STATUS 白名单，而 refundCardAssets 带；
 * 合并前需确认「冻结卡能否接收退差」这一业务口径，属独立授权包。
 * 此处不再登记行号——行号每次编辑都会漂移，按方法名定位。
 */
@Mapper
public interface TradeCardMapper extends BaseMapper<WsCard> {

    /**
     * 锁定水卡行（SELECT ... FOR UPDATE，CARD-SCOPE 下单事务②③步）。
     * <p>绕过 {@code @TableLogic} 读取全部 DATA_STATUS：逻辑删除卡也要锁到并在代码里显式拒绝，
     * 否则「已删除」在锁语义上等于「不存在」，并发恢复/删除窗口会漏判。归属/状态/范围校验
     * 必须基于锁内读取的行，预检结论一概不作数（预检不是安全边界）。</p>
     *
     * @param cardId 卡ID
     * @return 含 DATA_STATUS 的卡行；不存在返回 null
     */
    WsCard selectByIdForUpdate(@Param("cardId") Long cardId);

    /**
     * 余额支付原子扣减（payWay=2）。影响行数=1 视为成功；=0 说明卡不存在/归属漂移/非正常态/已过期/余额不足。
     *
     * @param cardId              卡ID
     * @param amountFen           扣减金额(分)
     * @param expectedOwnerUserId 锁内读出的卡主ID（USER_ID 归属条件；成员取水时≠actorUserId）
     * @param actorUserId         实际使用人ID（UPDATE_BY；卡主取水时与 expectedOwnerUserId 相同）
     * @param now                 yyyyMMddHHmmss，用于过期判定与 UPDATE_TIME
     * @return 影响行数
     */
    int deductBalance(@Param("cardId") Long cardId,
                      @Param("amountFen") Long amountFen,
                      @Param("expectedOwnerUserId") Long expectedOwnerUserId,
                      @Param("actorUserId") Long actorUserId,
                      @Param("now") String now);

    /**
     * 水量支付原子扣减（payWay=3）。语义同上，扣 BALANCE_ML。
     *
     * @param cardId              卡ID
     * @param planMl              扣减水量(毫升)
     * @param expectedOwnerUserId 锁内读出的卡主ID
     * @param actorUserId         实际使用人ID（UPDATE_BY）
     * @param now                 yyyyMMddHHmmss
     * @return 影响行数
     */
    int deductMl(@Param("cardId") Long cardId,
                 @Param("planMl") Long planMl,
                 @Param("expectedOwnerUserId") Long expectedOwnerUserId,
                 @Param("actorUserId") Long actorUserId,
                 @Param("now") String now);

    /**
     * 金额 + 水量统一权益返还（E2E-04 包A 唯一合法的返还入口）：一条 UPDATE 同时加回两列。
     *
     * <p><b>调用序列固定，禁止任何变体</b>：
     * {@code selectByIdForUpdate} 锁卡读前态 → 锁内核验（存在/未删除/归属/未注销）→ 封顶重算
     * → 本方法，影响行必须 {@code == 1} → 流水 AFTER 用 {@code Math.addExact(前态, delta)} 的<b>预期值</b>
     * 写入（铁律③，不再 selectById 重读）。不先锁卡读前态，双列前态条件在并发下必现 0 行假失败。</p>
     *
     * <p>影响行 {@code != 1} 时禁止笼统抛「返还失败」：用锁内读到的那一行精确归类
     * （卡不存在 / 逻辑删除 / 换主 / 注销 / 前态漂移=账本断裂 / 入参方向非法），落 5需人工对账。</p>
     *
     * <p>金额上限（R0-3 累计封顶）无法由本 SQL 表达——它看不见 {@code ws_wallet_flow} 与售后历史，
     * 由「锁定读的四元额度聚合 + uk_after_sale_source + uk_wallet_flow_biz_key」三层承担，别指望这条 CAS。</p>
     *
     * @param cardId              卡ID
     * @param amountFen           返还金额(分)，必须 {@code >= 0}；payWay=3 的水品返还只退配送费时此列即配送费
     * @param ml                  返还水量(毫升)，必须 {@code >= 0}；payWay=2 恒传 0
     * @param oldAmount           锁内读到的 BALANCE_AMOUNT 前态
     * @param oldMl               锁内读到的 BALANCE_ML 前态
     * @param expectedOwnerUserId 锁内读到的卡主ID（归属条件，钱绝不打进别人的卡）
     * @param opUserId            操作人ID（UPDATE_BY）
     * @param now                 yyyyMMddHHmmss
     * @return 影响行数（必须为 1）
     */
    int refundCardAssets(@Param("cardId") Long cardId,
                         @Param("amountFen") Long amountFen,
                         @Param("ml") Long ml,
                         @Param("oldAmount") Long oldAmount,
                         @Param("oldMl") Long oldMl,
                         @Param("expectedOwnerUserId") Long expectedOwnerUserId,
                         @Param("opUserId") Long opUserId,
                         @Param("now") String now);

    /**
     * 金额 + 水量统一权益<b>冲减</b>（E2E-04 包D-5 外部退款成功后的唯一出账口径）。
     *
     * <p>它是 {@link #refundCardAssets} 的镜像：一条 UPDATE 同时减两列，WHERE 带双列前态与归属。
     * 语义完全不同——那边是「钱退回卡里」，这边是「钱已经原路退回用户的支付账户，
     * 卡上对应的权益必须同时消失」。少了这一步，用户既收到退款又留着水量。</p>
     *
     * <p>{@code BALANCE_* >= 扣减量} 与双列前态并存不是冗余：前态保证「扣的是我读到的那张卡」，
     * 余量条件保证「不会扣成负数」。批次侧的剩余可能因为并发消费已经变了，
     * 此时影响 0 行、整事务回滚，退款结算转人工——绝不允许卡余额被扣成负数。</p>
     *
     * <p>{@code CARD_STATUS IN (1, 2, 3)} 与返还同一白名单：冻结卡、过期卡的充值同样可以退款，
     * 但已注销(4)的卡不再参与任何权益变动。</p>
     *
     * @param cardId              卡ID
     * @param amountFen           冲减金额(分)，{@code >= 0}
     * @param ml                  冲减水量(毫升)，{@code >= 0}
     * @param oldAmount           锁内读到的 BALANCE_AMOUNT 前态
     * @param oldMl               锁内读到的 BALANCE_ML 前态
     * @param expectedOwnerUserId 锁内读到的卡主ID
     * @param opUserId            操作人ID
     * @param now                 yyyyMMddHHmmss
     * @return 影响行数（必须为 1）
     */
    int reverseCardAssets(@Param("cardId") Long cardId,
                          @Param("amountFen") Long amountFen,
                          @Param("ml") Long ml,
                          @Param("oldAmount") Long oldAmount,
                          @Param("oldMl") Long oldMl,
                          @Param("expectedOwnerUserId") Long expectedOwnerUserId,
                          @Param("opUserId") Long opUserId,
                          @Param("now") String now);

    /**
     * 首购退款后的水卡注销（E2E-04 包D-5，R0 规则）。
     *
     * <p>{@code BALANCE_AMOUNT = 0 AND BALANCE_ML = 0} 是本 SQL 最要害的两条谓词：
     * 注销一张还带着权益的卡，等于把用户没退款的那部分余额一并作废。
     * 判定「能不能注销」的业务条件（是否还有别的批次、成员授权、未完成订单）在
     * {@code CardClosureRule} 里，这里只做最后一道物理防线。</p>
     *
     * <p>不删卡、不删流水（任务书 3.4 明令）：注销只是状态变化，历史可追溯性必须保留。</p>
     *
     * @return 影响行数；0 行说明卡上仍有权益或状态/归属已变，调用方必须回滚
     */
    int closeEmptyCard(@Param("cardId") Long cardId,
                       @Param("expectedOwnerUserId") Long expectedOwnerUserId,
                       @Param("opUserId") Long opUserId,
                       @Param("now") String now);

    /**
     * 重算卡的聚合有效期（E2E-04 包D-5）：退掉一个批次后，卡的有效期应等于剩余批次里最晚的那个。
     *
     * <p>为什么必须重算：卡的 {@code EXPIRE_TIME} 是所有批次有效期的聚合上界。
     * 退掉贡献了最晚有效期的那个批次而不重算，卡就会带着一段<b>用户已经退款了的有效期</b>继续可用。</p>
     *
     * <p>前态 {@code EXPIRE_TIME} 进 WHERE（NULL 前态走 IS NULL 分支）：并发续期与本次重算撞上时
     * 影响 0 行、整事务回滚，绝不用一个算旧了的值覆盖刚续上的有效期。
     * {@code newExpireTime} 允许为 NULL，表示剩余批次里有永久批次。</p>
     *
     * @return 影响行数（调用方要求 1；等值时不应调用本方法）
     */
    int resetAggregateExpireTime(@Param("cardId") Long cardId,
                                 @Param("newExpireTime") String newExpireTime,
                                 @Param("oldExpireTime") String oldExpireTime,
                                 @Param("expectedOwnerUserId") Long expectedOwnerUserId,
                                 @Param("opUserId") Long opUserId,
                                 @Param("now") String now);

    /**
     * 余额补偿入账（退差/退款，payWay=2）：原子 UPDATE 加回余额（铁律1，禁读-算-写）。
     * 退差必须回到<b>同一张卡主的卡</b>：USER_ID 归属条件确保卡归属漂移时补偿失败并回滚，而不是打进别人的卡。
     *
     * @param cardId              卡ID
     * @param amountFen           补偿金额(分)，正数
     * @param expectedOwnerUserId 卡主ID（卡归属条件）
     * @param opUserId            操作人ID（UPDATE_BY；成员订单结算时为成员）
     * @param now                 yyyyMMddHHmmss
     * @return 影响行数
     * @deprecated 仅为 E2E-01 取水退差历史路径保留（{@code TradeOrderTxServiceImpl.settleWaterOrder}）。
     * 它不校验 {@code CARD_STATUS}、不带余额前态，新代码一律用 {@link #refundCardAssets}；
     * 迁移前置条件与被推迟的理由见类注释的「迁移待办」。
     */
    @Deprecated
    int compensateBalance(@Param("cardId") Long cardId,
                          @Param("amountFen") Long amountFen,
                          @Param("expectedOwnerUserId") Long expectedOwnerUserId,
                          @Param("opUserId") Long opUserId,
                          @Param("now") String now);

    /**
     * 水量补偿入账（退差/退款，payWay=3）：原子 UPDATE 加回水量，归属条件同上。
     *
     * @param cardId              卡ID
     * @param ml                  补偿水量(毫升)，正数
     * @param expectedOwnerUserId 卡主ID（卡归属条件）
     * @param opUserId            操作人ID（UPDATE_BY）
     * @param now                 yyyyMMddHHmmss
     * @return 影响行数
     * @deprecated 同 {@link #compensateBalance}：仅 E2E-01 取水退差历史路径可用，新代码用 {@link #refundCardAssets}。
     */
    @Deprecated
    int compensateMl(@Param("cardId") Long cardId,
                     @Param("ml") Long ml,
                     @Param("expectedOwnerUserId") Long expectedOwnerUserId,
                     @Param("opUserId") Long opUserId,
                     @Param("now") String now);
}
