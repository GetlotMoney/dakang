package com.jbk.serve.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.user.po.WsCardMember;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 水卡成员授权 Mapper。
 *
 * <p>CARD-MEMBER：uk_card_member_user(CARD_ID, MEMBER_USER_ID) 不含 DATA_STATUS——
 * 逻辑删除的授权记录仍占用唯一键，重新授权必须复用原记录。因此本 Mapper 的手写 SQL 一律
 * <b>跨全部 DATA_STATUS</b> 读写（BaseMapper 会被 {@code @TableLogic} 自动过滤，只用于常规有效行查询）。
 * 列显式 AS 别名，杜绝映射对 mapUnderscoreToCamelCase 配置的依赖。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Mapper
public interface WsCardMemberMapper extends BaseMapper<WsCardMember> {

    String COLUMNS = "ID AS id, DATA_STATUS AS dataStatus, CREATE_BY AS createBy, CREATE_TIME AS createTime, "
            + "UPDATE_BY AS updateBy, UPDATE_TIME AS updateTime, CARD_ID AS cardId, "
            + "MEMBER_USER_ID AS memberUserId, MEMBER_NAME AS memberName, DAY_LIMIT_ML AS dayLimitMl, "
            + "EFFECTIVE_TIME AS effectiveTime, EXPIRE_TIME AS expireTime, MEMBER_STATUS AS memberStatus";

    /**
     * 取水事务锁成员关系（FOR UPDATE，命中 uk_card_member_user 单行锁）：日限额靠「先锁成员关系、再统计当天订单」串行化，不锁会并发突破限额。
     * 跨全部 DATA_STATUS：删除态也要锁到再由 Java 侧显式拒绝（与锁卡口径一致）。
     */
    @Select("SELECT " + COLUMNS + " FROM ws_card_member "
            + "WHERE CARD_ID = #{cardId} AND MEMBER_USER_ID = #{memberUserId} FOR UPDATE")
    WsCardMember selectByCardAndUserForUpdate(@Param("cardId") Long cardId,
                                              @Param("memberUserId") Long memberUserId);

    /** 按唯一键读取（跨全部 DATA_STATUS）：重新授权复用原记录的依据。 */
    @Select("SELECT " + COLUMNS + " FROM ws_card_member "
            + "WHERE CARD_ID = #{cardId} AND MEMBER_USER_ID = #{memberUserId}")
    WsCardMember selectByCardAndUserIncludingDeleted(@Param("cardId") Long cardId,
                                                     @Param("memberUserId") Long memberUserId);

    /** 按主键读取（跨全部 DATA_STATUS）：编辑/撤销前定位记录，删除态由服务层显式拒绝。 */
    @Select("SELECT " + COLUMNS + " FROM ws_card_member WHERE ID = #{id}")
    WsCardMember selectByIdIncludingDeleted(@Param("id") Long id);

    /**
     * 新建授权（显式写入各列，绕开 MP 自动填充，审计字段完全由服务层可控）。
     * 并发重加命中 uk_card_member_user 时抛 DuplicateKeyException，由服务层转为复用原记录。
     */
    @Insert("INSERT INTO ws_card_member(DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, "
            + "CARD_ID, MEMBER_USER_ID, MEMBER_NAME, DAY_LIMIT_ML, EFFECTIVE_TIME, EXPIRE_TIME, MEMBER_STATUS) "
            + "VALUES(0, #{createBy}, #{createTime}, #{updateBy}, #{updateTime}, #{cardId}, #{memberUserId}, "
            + "#{memberName}, #{dayLimitMl}, #{effectiveTime}, #{expireTime}, 1)")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "ID")
    int insertMember(WsCardMember row);

    /**
     * 重新授权/编辑：复用唯一键定位的原记录（含逻辑删除/已解除态），整行回到生效态。
     * WHERE 带 CARD_ID 双保险——ID 与卡不匹配时影响 0 行，由服务层校验拒绝。
     * 不改 MEMBER_USER_ID：换人=撤销+重加，由服务层前置校验保证。
     */
    @Update("UPDATE ws_card_member SET DATA_STATUS = 0, MEMBER_STATUS = 1, MEMBER_NAME = #{memberName}, "
            + "DAY_LIMIT_ML = #{dayLimitMl}, EFFECTIVE_TIME = #{effectiveTime}, EXPIRE_TIME = #{expireTime}, "
            + "UPDATE_BY = #{actor}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND CARD_ID = #{cardId}")
    int reauthorize(@Param("id") Long id,
                    @Param("cardId") Long cardId,
                    @Param("memberName") String memberName,
                    @Param("dayLimitMl") Long dayLimitMl,
                    @Param("effectiveTime") String effectiveTime,
                    @Param("expireTime") String expireTime,
                    @Param("actor") Long actor,
                    @Param("now") String now);

    /**
     * 撤销授权：条件状态更新（仅 1生效 且未删除 → 2已解除），影响行数由服务层校验。
     * 条件带 CARD_ID：防止用别的卡的 memberId 越权撤销（归属在 WHERE 内强制）。
     */
    @Update("UPDATE ws_card_member SET MEMBER_STATUS = 2, UPDATE_BY = #{actor}, UPDATE_TIME = #{now} "
            + "WHERE ID = #{id} AND CARD_ID = #{cardId} AND MEMBER_STATUS = 1 AND DATA_STATUS = 0")
    int revokeActive(@Param("id") Long id,
                     @Param("cardId") Long cardId,
                     @Param("actor") Long actor,
                     @Param("now") String now);
}
