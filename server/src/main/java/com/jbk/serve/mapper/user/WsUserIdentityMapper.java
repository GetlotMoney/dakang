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
 * L2-AUTH 身份专用 Mapper：手写 SQL，<b>跨全部 DATA_STATUS（含逻辑删除）</b>读取与写入。
 *
 * <p>不继承 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper}，因此不受 {@code BaseEntity} 上
 * {@code @TableLogic} 的自动 {@code DATA_STATUS=0} 过滤影响——身份唯一性判定必须看见被逻辑删除的账号，
 * 否则已删除的 openid/手机号会被误判为「不存在」而进入 UNBOUND 建户或重复占号（复审 P1-1）。</p>
 *
 * <p>openid 比较一律大小写敏感，但有两种写法且都正确，勿"统一"：列自身是 {@code utf8mb4_bin}，
 * 普通 {@code =} 已经大小写敏感且能走索引；旧查询额外加的 {@code BINARY} 是冗余的，还会让优化器
 * 放弃索引查找退化为全索引扫（实测 {@code type: index}）。新增的锁定读因此不带 {@code BINARY}
 * （实测 {@code type: const}）。列显式 AS 别名，杜绝映射歧义。</p>
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
     * 按 openid 锁定读；大小写敏感由列自身的 utf8mb4_bin 排序规则保证，无需 BINARY 因而走得到索引。
     *
     * <p>专供「插入撞唯一键后重读胜出行」：REPEATABLE READ 下同事务的普通 SELECT 仍取建立快照时的旧视图，
     * 看不见并发事务刚提交的那一行，重读必然还是空、把正常竞态误报成建号失败；FOR UPDATE 是锁定读，
     * 恒取最新已提交数据。仅在唯一键已报冲突（行必然存在）时调用，不会退化成空区间的间隙锁。</p>
     */
    @Select("SELECT " + COLUMNS + " FROM ws_user WHERE WECHAT_XCX_OPENID = #{openid} FOR UPDATE")
    List<WsUser> selectByOpenidForUpdate(@Param("openid") String openid);

    /**
     * 按主键读取（跨全部 DATA_STATUS）。
     *
     * <p>用于 CAS 影响 0 行后区分成因：账号已绑号（前端上下文陈旧，出路是刷新）与账号被禁用/删除
     * （出路是找客服）——两者出路相反，不查明就只能糊成一句用户无从下手的提示。</p>
     */
    @Select("SELECT " + COLUMNS + " FROM ws_user WHERE ID = #{id}")
    WsUser selectByIdIncludingDeleted(@Param("id") Long id);

    /** 按手机号读取全部状态记录；正常应 0 或 1 条，>1 为身份污染，由服务层拒绝。 */
    @Select("SELECT " + COLUMNS + " FROM ws_user WHERE USER_PHONE = #{phone}")
    List<WsUser> selectByPhoneIncludingDeleted(@Param("phone") String phone);

    /**
     * CAS 绑定 openid 到「有效且尚未绑定 openid」的手机号用户：
     * 精确匹配 ID+手机号，且 DATA_STATUS=0、DISABLED_FLAG=1、USER_STATUS=1、openid 仍为空。
     * 影响行数必须为 1；并发下另一请求会看到 openid 已非空而影响 0 行，服务层据此 fail-closed 不签发 Token。
     */
    @Update("UPDATE ws_user SET WECHAT_XCX_OPENID = #{openid}, UPDATE_BY = #{actor}, UPDATE_TIME = #{time} "
            + "WHERE ID = #{id} AND USER_PHONE = #{phone} AND DATA_STATUS = 0 AND DISABLED_FLAG = 1 "
            + "AND USER_STATUS = 1 AND (WECHAT_XCX_OPENID IS NULL OR WECHAT_XCX_OPENID = '')")
    int bindOpenidToUsablePhoneUser(@Param("id") Long id,
                                    @Param("phone") String phone,
                                    @Param("openid") String openid,
                                    @Param("actor") Long actor,
                                    @Param("time") String time);

    /**
     * CAS 为「仅微信身份建号、手机号仍为空」的用户补绑手机号（登录后在小程序内自助补绑）。
     *
     * <p>{@code USER_PHONE IS NULL} 是核心守卫：已绑号的账号绝不允许被本路径改号（换绑是另一条需身份
     * 复核的动线）。影响行数必须为 1；并发重复提交只有一个成功，其余 0 行由服务层 fail-closed。
     * 手机号已归属他人时会撞 uk_user_phone 抛 DuplicateKeyException，同样不会写脏。</p>
     */
    @Update("UPDATE ws_user SET USER_PHONE = #{phone}, UPDATE_BY = #{actor}, UPDATE_TIME = #{time} "
            + "WHERE ID = #{id} AND USER_PHONE IS NULL AND DATA_STATUS = 0 AND DISABLED_FLAG = 1 "
            + "AND USER_STATUS = 1")
    int bindPhoneToPhonelessUser(@Param("id") Long id,
                                 @Param("phone") String phone,
                                 @Param("actor") Long actor,
                                 @Param("time") String time);

    /**
     * CAS 更新昵称（用户自助编辑资料）：只作用于有效账号，影响行数必须为 1。
     * UPDATE_BY 记本人——自助行为不得记成系统操作（同补绑手机号的审计口径）。
     */
    @Update("UPDATE ws_user SET USER_NAME = #{userName}, UPDATE_BY = #{actor}, UPDATE_TIME = #{time} "
            + "WHERE ID = #{id} AND DATA_STATUS = 0 AND DISABLED_FLAG = 1 AND USER_STATUS = 1")
    int updateNicknameOfUsableUser(@Param("id") Long id,
                                   @Param("userName") String userName,
                                   @Param("actor") Long actor,
                                   @Param("time") String time);

    /**
     * CAS 更新头像路径（用户自助编辑资料）：只作用于有效账号，影响行数必须为 1。
     */
    @Update("UPDATE ws_user SET USER_AVATAR = #{avatarPath}, UPDATE_BY = #{actor}, UPDATE_TIME = #{time} "
            + "WHERE ID = #{id} AND DATA_STATUS = 0 AND DISABLED_FLAG = 1 AND USER_STATUS = 1")
    int updateAvatarOfUsableUser(@Param("id") Long id,
                                 @Param("avatarPath") String avatarPath,
                                 @Param("actor") Long actor,
                                 @Param("time") String time);

    /**
     * 建号后回填默认昵称（仅微信身份建号用；昵称要带账号 ID 尾号，而 ID 得插入后才有）。
     *
     * <p>{@code USER_NAME = #{expected}} 是守卫：只在昵称仍是占位值时才改，绝不覆盖用户已改过的昵称。</p>
     */
    @Update("UPDATE ws_user SET USER_NAME = #{userName}, UPDATE_BY = #{actor}, UPDATE_TIME = #{time} "
            + "WHERE ID = #{id} AND USER_NAME = #{expected}")
    int renamePlaceholderUserName(@Param("id") Long id,
                                  @Param("expected") String expected,
                                  @Param("userName") String userName,
                                  @Param("actor") Long actor,
                                  @Param("time") String time);

    /**
     * 新建最小用户主体（手机号从未注册时）。显式写入各列，绕开 MP 自动填充，字段完全可控；
     * 命中 USER_PHONE / WECHAT_XCX_OPENID 唯一约束时抛 DuplicateKeyException，由服务层 fail-closed。
     */
    @Insert("INSERT INTO ws_user(USER_NAME, USER_GENDER, USER_PHONE, DISABLED_FLAG, USER_STATUS, POINTS, "
            + "WECHAT_XCX_OPENID, DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME) "
            + "VALUES(#{userName}, #{userGender}, #{userPhone}, #{disabledFlag}, #{userStatus}, #{points}, "
            + "#{wechatXcxOpenid}, #{dataStatus}, #{createBy}, #{createTime}, #{updateBy}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "ID")
    int insertIdentityUser(WsUser user);
}
