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
 * @author dakang
 * @since 2026-07-19
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
     * 余额补偿入账（退差/退款，payWay=2）：原子 UPDATE 加回余额（铁律1，禁读-算-写）。
     * 退差必须回到<b>同一张卡主的卡</b>：USER_ID 归属条件确保卡归属漂移时补偿失败并回滚，而不是打进别人的卡。
     *
     * @param cardId              卡ID
     * @param amountFen           补偿金额(分)，正数
     * @param expectedOwnerUserId 卡主ID（卡归属条件）
     * @param opUserId            操作人ID（UPDATE_BY；成员订单结算时为成员）
     * @param now                 yyyyMMddHHmmss
     * @return 影响行数
     */
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
     */
    int compensateMl(@Param("cardId") Long cardId,
                     @Param("ml") Long ml,
                     @Param("expectedOwnerUserId") Long expectedOwnerUserId,
                     @Param("opUserId") Long opUserId,
                     @Param("now") String now);
}
