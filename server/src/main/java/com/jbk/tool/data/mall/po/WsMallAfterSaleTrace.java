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
 * 商城售后轨迹 Po（E2E-09 S4）：节点即状态到达史。
 *
 * <p>幂等键 {@code MAT:<afterSaleNo>:<node>} 由库层唯一；键被占用即证据冲突，整事务回滚——
 * 与 S3 履约轨迹同一口径，不再重复解释。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_after_sale_trace")
public class WsMallAfterSaleTrace extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "售后单ID")
    @TableField("AFTER_SALE_ID")
    private Long afterSaleId;

    @Schema(description = "售后单号快照")
    @TableField("AFTER_SALE_NO")
    private String afterSaleNo;

    @Schema(description = "到达节点(1399)")
    @TableField("TRACE_NODE")
    private Integer traceNode;

    @Schema(description = "操作方(1397)")
    @TableField("ACTOR_TYPE")
    private Integer actorType;

    @Schema(description = "操作人ID")
    @TableField("ACTOR_ID")
    private Long actorId;

    @Schema(description = "节点业务对象ID：质检记结论、换货记补发订单ID")
    @TableField("SUBJECT_ID")
    private Long subjectId;

    @Schema(description = "节点时间")
    @TableField("TRACE_TIME")
    private String traceTime;

    @Schema(description = "节点文案")
    @TableField("TRACE_TEXT")
    private String traceText;

    @Schema(description = "幂等键")
    @TableField("BIZ_IDEMPOTENCY_KEY")
    private String bizIdempotencyKey;
}
