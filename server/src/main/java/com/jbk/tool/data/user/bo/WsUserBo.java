package com.jbk.tool.data.user.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import com.jbk.tool.validator.group.PageGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import java.io.Serializable;

/**
 * C 端用户请求对象。
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsUserBo", description = "C端用户请求")
public class WsUserBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 与 CREATE_TIME 的 varchar(14) 存储格式逐字对应；Service 层复用同一常量，判据不分叉。 */
    public static final String TIME_14 = "^\\d{14}$";

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "用户信息不为空")
    private Long id;

    @Schema(description = "用户名称(max50)")
    @NotEmpty(groups = InsertGroup.class, message = "用户名称不为空")
    @Size(groups = InsertGroup.class, max = 50, message = "用户名称长度不能超过50")
    private String userName;

    @Schema(description = "性别(20)：1男 2女")
    @NotNull(groups = InsertGroup.class, message = "用户性别不为空")
    private Integer userGender;

    @Schema(description = "手机号(max20)")
    @NotEmpty(groups = InsertGroup.class, message = "手机号不为空")
    @Size(groups = InsertGroup.class, max = 20, message = "手机号长度不能超过20")
    private String userPhone;

    @Schema(description = "用户头像URL(max500)")
    @Size(groups = InsertGroup.class, max = 500, message = "用户头像URL长度不能超过500")
    private String userAvatar;

    // 以下四个筛选字段只出现在分页查询里，而分页入口用的是 @Validated(PageGroup.class)。
    // PageGroup 不继承 Default，所以不写 groups 的约束在这条路径上一条都不会执行——
    // 注解看着在、其实从没跑过。四条约束因此显式登记进 Default 与 PageGroup 两个组。
    // Service 层另有一道同义校验，两处都在，缺一处也不至于放行脏条件。

    @Schema(description = "是否禁用(10)：1正常 2禁用（列表筛选）")
    private Integer disabledFlag;

    /**
     * 能力筛选取值只允许 {@code WsUserServiceImpl} 声明的两个常量，Service 层对未知值 fail-closed
     * 抛错——不做「不认识就当没传」的宽松回落，否则拼错一个字母就会把全量用户当成筛选结果返回。
     */
    @Schema(description = "能力筛选：OWNER_VIEW 机主 / COURIER_WORK 配送员")
    @Size(max = 20, message = "能力条件不正确", groups = { Default.class, PageGroup.class })
    private String capability;

    /**
     * 注册时间区间。CREATE_TIME 是 varchar(14) 的 yyyyMMddHHmmss，比较走字符串序：
     * 传 '2026-08-13' 这类带分隔符的值时 '-'(0x2D) &lt; '0'(0x30)，上界会把当年记录全部排除
     * 而不报错——静默返回空表比没有筛选更难排查。故此处强制 14 位纯数字，格式不符直接拒绝。
     */
    @Schema(description = "注册时间起(yyyyMMddHHmmss)")
    @Pattern(regexp = TIME_14, message = "注册开始时间格式不正确", groups = { Default.class, PageGroup.class })
    private String createTimeBegin;

    @Schema(description = "注册时间止(yyyyMMddHHmmss)")
    @Pattern(regexp = TIME_14, message = "注册结束时间格式不正确", groups = { Default.class, PageGroup.class })
    private String createTimeEnd;

}
