package com.jbk.serve.mapper.user;

import com.jbk.tool.data.user.po.WsUser;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * L2-AUTH 身份专用 Mapper：手写 SQL，跨全部 DATA_STATUS 读写。不继承 BaseMapper，避开 {@code @TableLogic} 自动过滤——
 * 身份唯一性判定必须看见被逻辑删除的账号（复审 P1-1）。
 * openid 列为 utf8mb4_bin，普通 = 已大小写敏感且走索引；旧查询的 BINARY 冗余且退化为全索引扫，两种写法并存勿"统一"。
 */
@Mapper
public interface WsUserIdentityMapper {

    String COLUMNS = "ID AS id, USER_NAME AS userName, USER_GENDER AS userGender, USER_PHONE AS userPhone, "
            + "USER_AVATAR AS userAvatar, "
            + "DISABLED_FLAG AS disabledFlag, USER_STATUS AS userStatus, POINTS AS points, "
            + "WECHAT_XCX_OPENID AS wechatXcxOpenid, DATA_STATUS AS dataStatus, "
            + "CREATE_BY AS createBy, CREATE_TIME AS createTime, UPDATE_BY AS updateBy, UPDATE_TIME AS updateTime";

    /** 按 openid 精确（大小写敏感）读取全部状态记录；正常应 0 或 1 条，>1 为身份污染，由服务层拒绝。 */
    @Select("SELECT " + COLUMNS + " FROM ws_user WHERE BINARY WECHAT_XCX_OPENID = #{openid}")
    List<WsUser> selectByOpenidIncludingDeleted(@Param("openid") String openid);

    /**
     * 按 openid 锁定读，专供插入撞唯一键后重读胜出行：RR 下普通 SELECT 走旧快照看不见并发刚提交的行，FOR UPDATE 恒取最新已提交。
     * 仅在唯一键已冲突（行必然存在）时调用，不会退化成空区间间隙锁。
     */
    @Select("SELECT " + COLUMNS + " FROM ws_user WHERE WECHAT_XCX_OPENID = #{openid} FOR UPDATE")
    List<WsUser> selectByOpenidForUpdate(@Param("openid") String openid);

    /** 按主键读取（跨全部 DATA_STATUS）：CAS 0 行后区分「已绑号（刷新）」与「被禁用/删除（找客服）」两种出路。 */
    @Select("SELECT " + COLUMNS + " FROM ws_user WHERE ID = #{id}")
    WsUser selectByIdIncludingDeleted(@Param("id") Long id);

    /**
     * 只取手机号，供绑号闸判定。刻意不复用整行读取：整行会把 ws_user 全部列变成五条资金链的硬依赖。
     * 返回 null=账号不存在；空白=存在但未绑号，两者都判拒绝。
     */
    @Select("SELECT USER_PHONE FROM ws_user WHERE ID = #{id}")
    String selectPhoneByIdIncludingDeleted(@Param("id") Long id);

    /** 按手机号读取全部状态记录；正常应 0 或 1 条，>1 为身份污染，由服务层拒绝。 */
    @Select("SELECT " + COLUMNS + " FROM ws_user WHERE USER_PHONE = #{phone}")
    List<WsUser> selectByPhoneIncludingDeleted(@Param("phone") String phone);

    /** CAS 绑定 openid 到有效且尚未绑定的手机号用户。影响行数必须为 1；并发 0 行由服务层 fail-closed 不签发 Token。 */
    @Update("UPDATE ws_user SET WECHAT_XCX_OPENID = #{openid}, UPDATE_BY = #{actor}, UPDATE_TIME = #{time} "
            + "WHERE ID = #{id} AND USER_PHONE = #{phone} AND DATA_STATUS = 0 AND DISABLED_FLAG = 1 "
            + "AND USER_STATUS = 1 AND (WECHAT_XCX_OPENID IS NULL OR WECHAT_XCX_OPENID = '')")
    int bindOpenidToUsablePhoneUser(@Param("id") Long id,
                                    @Param("phone") String phone,
                                    @Param("openid") String openid,
                                    @Param("actor") Long actor,
                                    @Param("time") String time);

    /**
     * CAS 为「仅微信身份建号」的用户补绑手机号。USER_PHONE IS NULL 是核心守卫：已绑号账号不许经本路径改号；
     * 号码归属他人时撞 uk_user_phone，同样不写脏。
     */
    @Update("UPDATE ws_user SET USER_PHONE = #{phone}, UPDATE_BY = #{actor}, UPDATE_TIME = #{time} "
            + "WHERE ID = #{id} AND USER_PHONE IS NULL AND DATA_STATUS = 0 AND DISABLED_FLAG = 1 "
            + "AND USER_STATUS = 1")
    int bindPhoneToPhonelessUser(@Param("id") Long id,
                                 @Param("phone") String phone,
                                 @Param("actor") Long actor,
                                 @Param("time") String time);

    /** CAS 更新昵称：只作用于有效账号；UPDATE_BY 记本人，自助行为不得记成系统操作。 */
    @Update("UPDATE ws_user SET USER_NAME = #{userName}, UPDATE_BY = #{actor}, UPDATE_TIME = #{time} "
            + "WHERE ID = #{id} AND DATA_STATUS = 0 AND DISABLED_FLAG = 1 AND USER_STATUS = 1")
    int updateNicknameOfUsableUser(@Param("id") Long id,
                                   @Param("userName") String userName,
                                   @Param("actor") Long actor,
                                   @Param("time") String time);

    /** CAS 更新头像路径：只作用于有效账号，影响行数必须为 1。 */
    @Update("UPDATE ws_user SET USER_AVATAR = #{avatarPath}, UPDATE_BY = #{actor}, UPDATE_TIME = #{time} "
            + "WHERE ID = #{id} AND DATA_STATUS = 0 AND DISABLED_FLAG = 1 AND USER_STATUS = 1")
    int updateAvatarOfUsableUser(@Param("id") Long id,
                                 @Param("avatarPath") String avatarPath,
                                 @Param("actor") Long actor,
                                 @Param("time") String time);

    /** 建号后回填默认昵称（昵称带账号 ID 尾号，ID 插入后才有）。USER_NAME = expected 守卫：绝不覆盖用户已改的昵称。 */
    @Update("UPDATE ws_user SET USER_NAME = #{userName}, UPDATE_BY = #{actor}, UPDATE_TIME = #{time} "
            + "WHERE ID = #{id} AND USER_NAME = #{expected}")
    int renamePlaceholderUserName(@Param("id") Long id,
                                  @Param("expected") String expected,
                                  @Param("userName") String userName,
                                  @Param("actor") Long actor,
                                  @Param("time") String time);

    /** 新建最小用户主体。显式列写入绕开 MP 自动填充；撞唯一约束抛 DuplicateKeyException，由服务层 fail-closed。 */
    @Insert("INSERT INTO ws_user(USER_NAME, USER_GENDER, USER_PHONE, DISABLED_FLAG, USER_STATUS, POINTS, "
            + "WECHAT_XCX_OPENID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME) "
            + "VALUES(#{userName}, #{userGender}, #{userPhone}, #{disabledFlag}, #{userStatus}, #{points}, "
            + "#{wechatXcxOpenid}, #{dataStatus}, #{createBy}, #{createTime}, #{updateBy}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "ID")
    int insertIdentityUser(WsUser user);
}
