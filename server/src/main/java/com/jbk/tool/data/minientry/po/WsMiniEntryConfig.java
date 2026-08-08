package com.jbk.tool.data.minientry.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 小程序入口配置 Po（S6）。
 *
 * @author dakang
 * @since 2026-08-07
 */
@Data
@Accessors(chain = true)
@TableName("ws_mini_entry_config")
public class WsMiniEntryConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "逻辑删除")
    @TableLogic
    @TableField("DATA_STATUS")
    private Integer dataStatus;

    @TableField("CREATE_BY")
    private Long createBy;

    @TableField("CREATE_TIME")
    private String createTime;

    @TableField("UPDATE_BY")
    private Long updateBy;

    @TableField("UPDATE_TIME")
    private String updateTime;

    @Schema(description = "入口键：功能入口=路由编号；内容位=protocol/faq/service/notice")
    @TableField("ENTRY_KEY")
    private String entryKey;

    @Schema(description = "入口类型(1384)：1功能入口 2内容链接 3维护公告")
    @TableField("ENTRY_TYPE")
    private Integer entryType;

    @Schema(description = "展示名称")
    @TableField("ENTRY_NAME")
    private String entryName;

    @Schema(description = "排序")
    @TableField("SORT_NO")
    private Integer sortNo;

    @Schema(description = "是否启用(1)：1否 2是")
    @TableField("ENABLED_FLAG")
    private Integer enabledFlag;

    @Schema(description = "跳转类型(1385)：1内部路由 2外部链接")
    @TableField("JUMP_TYPE")
    private Integer jumpType;

    @Schema(description = "内部路由编号")
    @TableField("ROUTE_ID")
    private String routeId;

    @Schema(description = "外部链接（https+白名单域名）")
    @TableField("EXTERNAL_URL")
    private String externalUrl;

    @Schema(description = "内容文本（公告正文）")
    @TableField("CONTENT_TEXT")
    private String contentText;

    @Schema(description = "配置状态(1386)：1草稿 2已发布 3已撤回")
    @TableField("CONFIG_STATUS")
    private Integer configStatus;

    @Schema(description = "最近发布时间")
    @TableField("PUBLISH_TIME")
    private String publishTime;

    @Schema(description = "乐观锁版本")
    @TableField("VERSION")
    private Integer version;
}
