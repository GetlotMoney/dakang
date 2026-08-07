package com.jbk.tool.data.aftersale.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 权益批次消费分摊 Po（E2E-04 包D，REQ-061）。
 *
 * <p>每笔取水/配送消费分摊到哪些批次。{@code uk_alloc_biz_batch (BIZ_KEY, BATCH_ID)}
 * 保证同一次消费对同一批次只分摊一次——消费事务重放时撞键回滚，
 * 而不是把批次剩余重复扣一遍（那会让卡聚合与批次剩余从此对不上）。</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_entitlement_allocation")
@Schema(name = "WsEntitlementAllocation", description = "权益批次消费分摊表")
public class WsEntitlementAllocation extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "被扣减的权益批次ID")
    private Long batchId;

    @Schema(description = "水卡ID")
    private Long cardId;

    @Schema(description = "对应的钱包流水ID")
    private Long flowId;

    @Schema(description = "触发消费的订单ID")
    private Long orderId;

    @Schema(description = "消费业务幂等键（如 DELIVERY:<orderNo>）")
    private String bizKey;

    @Schema(description = "同一次消费跨多批次时的分摊序号，从1开始")
    private Integer allocSeq;

    @Schema(description = "本次从该批次扣减的余额(分)，正数")
    private Long allocAmountFen;

    @Schema(description = "本次从该批次扣减的水量(毫升)，正数")
    private Long allocWaterMl;

    @Schema(description = "是否已被冲正：0否 1是")
    private Integer reversedFlag;
}
