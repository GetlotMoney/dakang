package com.jbk.tool.data.api.vo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * <p>
 * 
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("api_rabc_menu")
@Schema(name = "ApiRbacMenuVo", description = "$!{table.comment}")
public class ApiRbacMenuVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "菜单/功能点名称max20")
    @TableField("MENU_NAME")
    private String menuName;

    @Schema(description = "菜单类型50")
    @TableField("MENU_TYPE")
    private Integer menuType;

    @Schema(description = "菜单图标max150")
    @TableField("MENU_ICON")
    private String menuIcon;

    @Schema(description = "父菜单ID")
    @TableField("MENU_PARENT_ID")
    private Long menuParentId;

    @Schema(description = "顺序max10000")
    @TableField("MENU_SORT")
    private Integer menuSort;

    @Schema(description = "路由地址max150")
    @TableField("MENU_PATH")
    private String menuPath;

    @Schema(description = "组件路径max150")
    @TableField("MENU_COMPONENT")
    private String menuComponent;

    @Schema(description = "是否为外链1")
    @TableField("MENU_FRAME_FLAG")
    private Integer menuFrameFlag;

    @Schema(description = "外链地址max300")
    @TableField("MENU_FRAME_URL")
    private String menuFrameUrl;

    @Schema(description = "后端权限字符串max150")
    @TableField("MENU_API_PERMS")
    private String menuApiPerms;

    @Schema(description = "前端权限字符串max150")
    @TableField("MENU_WEB_PERMS")
    private String menuWebPerms;

    @Schema(description = "显示状态1")
    @TableField("MENU_VISIBLE_FLAG")
    private Integer menuVisibleFlag;

    @Schema(description = "禁用状态1")
    @TableField("MENU_DISABLED_FLAG")
    private Integer menuDisabledFlag;
}


