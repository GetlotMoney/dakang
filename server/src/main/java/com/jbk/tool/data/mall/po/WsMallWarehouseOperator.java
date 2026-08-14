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
 * 商城前置仓操作员归属 Po（E2E-09 S3）。
 *
 * <p>没有这张表，「仓库人员只能处理归属仓库订单」就只是一句文档：拣货/打包/分配
 * 只会把 operatorId 写进审计，任何已登录的管理端账号都能动别的仓的货。</p>
 *
 * @author dakang
 * @since 2026-08-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_warehouse_operator")
public class WsMallWarehouseOperator extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "前置仓ID")
    @TableField("WAREHOUSE_ID")
    private Long warehouseId;

    @Schema(description = "管理端操作员ID")
    @TableField("OPERATOR_ID")
    private Long operatorId;
}
