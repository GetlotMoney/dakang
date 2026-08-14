package com.jbk.tool.data.mall.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 商城配送员前置仓服务范围 Po（E2E-09 S3）。一期 ws_courier 的范围是水站集与自由文本，
 * 与商城前置仓不是同一套坐标系；商城域自持绑定表，"范围外"可判定、可拒绝、可测试。
 *
 * @author dakang
 * @since 2026-08-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_courier_scope")
public class WsMallCourierScope extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "前置仓ID")
    @TableField("WAREHOUSE_ID")
    private Long warehouseId;

    @Schema(description = "配送员ID")
    @TableField("COURIER_ID")
    private Long courierId;
}
