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
 * 商城履约轨迹 Po（E2E-09 S3）：节点即状态到达史。
 *
 * <p>幂等键 {@code MFT:<orderNo>:<node>} 由库层唯一——这是"重复点击不得重复写轨迹"的
 * 保证，不能只靠服务层查一遍再写。</p>
 *
 * @author dakang
 * @since 2026-08-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_fulfillment_trace")
public class WsMallFulfillmentTrace extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "履约任务ID")
    @TableField("FULFILL_ID")
    private Long fulfillId;

    @Schema(description = "商城订单号快照")
    @TableField("ORDER_NO")
    private String orderNo;

    @Schema(description = "到达节点(1396)")
    @TableField("TRACE_NODE")
    private Integer traceNode;

    @Schema(description = "操作方(1397)")
    @TableField("ACTOR_TYPE")
    private Integer actorType;

    @Schema(description = "操作人ID；系统写 0")
    @TableField("ACTOR_ID")
    private Long actorId;

    @Schema(description = "节点业务对象ID：分配节点记录被分配配送员ID")
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
