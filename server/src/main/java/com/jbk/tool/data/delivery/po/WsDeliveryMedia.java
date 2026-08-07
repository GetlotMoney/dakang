package com.jbk.tool.data.delivery.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 配送受控媒体表 Po（E2E-03 A5：三照/举证只以媒体键引用；库行是媒体存在与归属唯一权威）。
 * <p>提交举证时用条件 UPDATE 原子占用 BOUND_TASK_ID（WHERE IS NULL），
 * 防同一照片跨任务复用；跨用途/跨归属引用一律拒绝。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_delivery_media")
@Schema(name = "WsDeliveryMedia", description = "配送受控媒体表")
public class WsDeliveryMedia extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "受控媒体键(max64)")
    @TableField("MEDIA_KEY")
    private String mediaKey;

    @Schema(description = "登记人用户ID（ws_user.ID）")
    @TableField("OWNER_USER_ID")
    private Long ownerUserId;

    @Schema(description = "用途：1签收三照 2申诉举证 3异常举证")
    @TableField("MEDIA_PURPOSE")
    private Integer mediaPurpose;

    @Schema(description = "内容SHA-256（完整性校验元数据）")
    @TableField("CONTENT_SHA256")
    private String contentSha256;

    @Schema(description = "内容字节数")
    @TableField("SIZE_BYTES")
    private Long sizeBytes;

    @Schema(description = "MIME类型(max50)，仅允许 image/*")
    @TableField("MIME_TYPE")
    private String mimeType;

    @Schema(description = "已绑定任务ID（提交举证时原子占用；工单证据绑定工单ID）")
    @TableField("BOUND_TASK_ID")
    private Long boundTaskId;

    @Schema(description = "登记人门户(1364)：2用户 1管理端。员工与用户ID数值可能相同，引用校验必须门户+ID双匹配")
    @TableField("OWNER_PORTAL")
    private Integer ownerPortal;
}
