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
 * <p>openid 采用 {@code BINARY} 精确、大小写敏感比较；列显式 AS 别名，杜绝映射歧义。</p>
 */
@Mapper
public interface WsUserIdentityMapper {

    String COLUMNS = "ID AS id, USER_NAME AS userName, USER_GENDER AS userGender, USER_PHONE AS userPhone, "
            + "DISABLED_FLAG AS disabledFlag, USER_STATUS AS userStatus, POINTS AS points, "
            + "WECHAT_XCX_OPENID AS wechatXcxOpenid, DATA_STATUS AS dataStatus, "
            + "CREATE_BY AS createBy, CREATE_TIME AS createTime, UPDATE_BY AS updateBy, UPDATE_TIME AS updateTime";

    /** 按 openid 精确（大小写敏感）读取全部状态记录；正常应 0 或 1 条，>1 为身份污染，由服务层拒绝。 */
    @Select("SELECT " + COLUMNS + " FROM ws_user WHERE BINARY WECHAT_XCX_OPENID = #{openid}")
    List<WsUser> selectByOpenidIncludingDeleted(@Param("openid") String openid);

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
