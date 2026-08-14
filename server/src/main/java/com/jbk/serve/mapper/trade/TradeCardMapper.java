package com.jbk.serve.mapper.trade;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.user.po.WsCard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 交易域水卡原子扣减 Mapper（trade 自持，契约③共存约定）。
 * 资金铁律1：扣减必须 UPDATE ... WHERE 余量>=X 并校验影响行数，禁止读出→内存计算→写回。
 * CARD-MEMBER：卡归属条件由 {@code expectedOwnerUserId} 承担（锁内读出后显式传入，删掉即放弃库层归属防线）；UPDATE_BY 写实际操作人，两者语义不同。
 * 加法类 SQL 口径不同勿混用：{@link #refundCardAssets} 是新代码唯一合法返还入口（状态白名单+双列前态）；
 * {@link #compensateBalance}/{@link #compensateMl} 不校验 CARD_STATUS、无前态，仅 E2E-01 取水退差历史路径保留，迁移前需确认「冻结卡能否接收退差」口径。
 */
@Mapper
public interface TradeCardMapper extends BaseMapper<WsCard> {

    /**
     * 锁定水卡行（SELECT ... FOR UPDATE，CARD-SCOPE 下单事务②③步）。跨全部 DATA_STATUS：已删除卡也要锁到并显式拒绝，
     * 否则并发恢复/删除窗口漏判；归属/状态/范围校验必须基于锁内读取的行（预检不是安全边界）。
     *
     * @return 含 DATA_STATUS 的卡行；不存在返回 null
     */
    WsCard selectByIdForUpdate(@Param("cardId") Long cardId);

    /**
     * 余额支付原子扣减（payWay=2）。影响行数=1 成功；=0 即卡不存在/归属漂移/非正常态/已过期/余额不足。
     *
     * @param expectedOwnerUserId 锁内读出的卡主ID（成员取水时≠actorUserId）
     * @param now                 yyyyMMddHHmmss，用于过期判定与 UPDATE_TIME
     */
    int deductBalance(@Param("cardId") Long cardId,
                      @Param("amountFen") Long amountFen,
                      @Param("expectedOwnerUserId") Long expectedOwnerUserId,
                      @Param("actorUserId") Long actorUserId,
                      @Param("now") String now);

    /** 水量支付原子扣减（payWay=3）。语义同 {@link #deductBalance}，扣 BALANCE_ML。 */
    int deductMl(@Param("cardId") Long cardId,
                 @Param("planMl") Long planMl,
                 @Param("expectedOwnerUserId") Long expectedOwnerUserId,
                 @Param("actorUserId") Long actorUserId,
                 @Param("now") String now);

    /**
     * 金额 + 水量统一权益返还（E2E-04 包A 唯一合法返还入口）：一条 UPDATE 同时加回两列。
     * 调用序列固定：{@code selectByIdForUpdate} 锁卡读前态 → 锁内核验 → 封顶重算 → 本方法（影响行必须==1）→ 流水 AFTER 用 Math.addExact 预期值写入（铁律③）。
     * 影响行 != 1 时用锁内读到的行精确归类落 5需人工对账；R0-3 累计封顶本 SQL 表达不了，由锁定读聚合 + 两把唯一键承担。
     *
     * @param amountFen           返还金额(分)，必须 {@code >= 0}；payWay=3 的水品返还只退配送费时此列即配送费
     * @param ml                  返还水量(毫升)，必须 >= 0；payWay=2 恒传 0
     * @param oldAmount           锁内读到的 BALANCE_AMOUNT 前态
     * @param oldMl               锁内读到的 BALANCE_ML 前态
     * @param expectedOwnerUserId 锁内读到的卡主ID
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
     * 金额 + 水量统一权益冲减（E2E-04 包D-5 外部退款成功后的唯一出账口径）：钱已原路退回支付账户，卡上权益必须同时消失，否则用户既收退款又留水量。
     * 双列前态 + BALANCE_* >= 扣减量并存不冗余：前态保证扣的是读到的那张卡，余量保证不扣成负数；0 行整事务回滚转人工。
     * CARD_STATUS IN (1,2,3) 与返还同白名单：已注销(4)不再参与权益变动。
     *
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
     * 首购退款后的水卡注销（E2E-04 包D-5，R0 规则）。BALANCE_AMOUNT=0 AND BALANCE_ML=0 是最后一道物理防线：不得注销还带权益的卡；
     * 业务判定在 {@code CardClosureRule}。不删卡、不删流水（任务书 3.4）。
     *
     * @return 影响行数；0 行说明卡上仍有权益或状态/归属已变，调用方必须回滚
     */
    int closeEmptyCard(@Param("cardId") Long cardId,
                       @Param("expectedOwnerUserId") Long expectedOwnerUserId,
                       @Param("opUserId") Long opUserId,
                       @Param("now") String now);

    /**
     * 重算卡聚合有效期（E2E-04 包D-5）：卡 EXPIRE_TIME 是全部批次的聚合上界，退掉最晚批次不重算会留下已退款的有效期。
     * 前态 EXPIRE_TIME 进 WHERE（NULL 走 IS NULL 分支）：与并发续期撞上即 0 行回滚；newExpireTime 为 NULL 表示剩余有永久批次。
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
     * 余额补偿入账（退差，payWay=2）：原子 UPDATE 加回余额；USER_ID 归属条件保证不打进别人的卡。
     *
     * @deprecated 仅为 E2E-01 取水退差历史路径保留（{@code TradeOrderTxServiceImpl.settleWaterOrder}）；不校验 CARD_STATUS、不带前态，新代码一律用 {@link #refundCardAssets}。
     */
    @Deprecated
    int compensateBalance(@Param("cardId") Long cardId,
                          @Param("amountFen") Long amountFen,
                          @Param("expectedOwnerUserId") Long expectedOwnerUserId,
                          @Param("opUserId") Long opUserId,
                          @Param("now") String now);

    /**
     * 水量补偿入账（退差，payWay=3）：原子 UPDATE 加回水量，归属条件同上。
     *
     * @deprecated 同 {@link #compensateBalance}：仅 E2E-01 取水退差历史路径可用，新代码用 {@link #refundCardAssets}。
     */
    @Deprecated
    int compensateMl(@Param("cardId") Long cardId,
                     @Param("ml") Long ml,
                     @Param("expectedOwnerUserId") Long expectedOwnerUserId,
                     @Param("opUserId") Long opUserId,
                     @Param("now") String now);
}
