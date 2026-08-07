package com.jbk.tool.data.aftersale.po;

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
 * 售后执行动作表 Po（E2E-04 包A；严格对齐 deploy/mysql/migrations/2026-07-29-aftersale-e2e04-a.sql）。
 *
 * <p>三条来源（待接单取消 / 配送申诉补偿 / 取水异常核账）共用这一张执行表：
 * 差异只在触发时机与 SOURCE_*，返还内核完全相同；拆成「案件表 + 动作表」会让返还逻辑有两个入口。</p>
 *
 * <p><b>四元额度是本表的要害</b>（REFUND_PRODUCT_FEN / REFUND_SERVICE_FEN / REFUND_PRODUCT_ML
 * + 合计列 REFUND_AMOUNT）：payWay=2 时水费与配送费**都从余额扣**，若只按混合总额判累计封顶，
 * 连续多次 SERVICE_FEE_ONLY 申诉会各自拿「订单总额」当额度，把水费额度挪去退配送费——
 * 本单实际只扣过 N 分配送费却能退出 3N。故封顶必须按「水品 / 配送费」两条独立维度分别判定，
 * 各自的额度锚点见对应字段注释；REFUND_AMOUNT 只供余额 CAS 与流水使用，**不参与封顶判定**。</p>
 *
 * <p>额度锚点一律取订单快照（ws_order.PACKAGE_SNAP）而非当前价目表：
 * 价目调整后按新价重算历史单，会与原扣款流水对不上，而封顶基准正是原扣款流水。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_after_sale_action")
@Schema(name = "WsAfterSaleAction", description = "售后执行动作表")
public class WsAfterSaleAction extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "售后号(max32)：AS + sha256(sourceType:sourceId) 前30位大写，确定性派生。"
            + "同来源重放得同号，撞 uk_after_sale_no 即幂等命中，不靠应用层查重")
    @TableField("AFTER_SALE_NO")
    private String afterSaleNo;

    @Schema(description = "售后来源(1370)：1配送取消 2配送申诉 3取水异常核账")
    @TableField("SOURCE_TYPE")
    private Integer sourceType;

    @Schema(description = "来源主体ID：1取ws_order.ID 2取ws_delivery_appeal.ID 3取ws_order.ID。"
            + "申诉必须取 appealId——uk_appeal_active_task 只约束待处理申诉，同一任务可合法产生多条已裁决申诉，"
            + "用 taskId 当来源键会把第二次合法申诉误判为重放")
    @TableField("SOURCE_ID")
    private Long sourceId;

    @Schema(description = "关联订单ID：额度聚合（按订单维度累计已返还）与账本核验的锚点")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "订单归属用户ID：CAS 与归属核验的条件之一，防止把返还打到他人卡上")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "返还目标卡ID：内部返还必填；首次购卡未入账退款尚未发卡时可空")
    @TableField("CARD_ID")
    private Long cardId;

    @Schema(description = "动作类型(1371)：1卡内退款 2卡内补偿 3机构退款(包B) 4补送(包C)")
    @TableField("ACTION_TYPE")
    private Integer actionType;

    @Schema(description = "补偿策略码(max20)：PRODUCT_ONLY/SERVICE_FEE_ONLY/PRODUCT_AND_SERVICE/RESEND/REJECT。"
            + "字符串码不入字典——api_dict_data.DICT_VALUE 是 tinyint、DICT_LABEL 仅 varchar(10)，物理放不下；"
            + "单一出处见 AfterSaleEnum.StrategyCode。取消与取水核账无策略，置 NULL")
    @TableField("STRATEGY_CODE")
    private String strategyCode;

    @Schema(description = "运营批准的受影响数量（桶）：申诉补偿的数量边界，须 <= 快照 deliveryCount；"
            + "取消与取水核账为 NULL")
    @TableField("APPROVED_COUNT")
    private Integer approvedCount;

    @Schema(description = "水品权益返还金额(分)，payWay=2 专用。"
            + "独立成列是为了按「水品」这一维单独累计封顶，额度锚点=订单快照 waterAmountFen；"
            + "与配送费混算会让配送费申诉从水费额度里出钱")
    @TableField("REFUND_PRODUCT_FEN")
    private Long refundProductFen;

    @Schema(description = "配送费返还金额(分)，两种 payWay 通用（配送费恒以分计价）。"
            + "额度锚点=订单快照 deliveryFeeFen，与水品维各判各的，互不借用")
    @TableField("REFUND_SERVICE_FEN")
    private Long refundServiceFen;

    @Schema(description = "水品权益返还水量(毫升)，payWay=3 专用。"
            + "额度锚点=订单快照 waterMl。payWay=3 下水品走水量、配送费走金额，两维物理隔离")
    @TableField("REFUND_PRODUCT_ML")
    private Long refundProductMl;

    @Schema(description = "返还金额合计(分)=REFUND_PRODUCT_FEN+REFUND_SERVICE_FEN，供余额 CAS 与流水金额使用。"
            + "封顶判定**不看本列**——它是混合总额，用它判额度正是「配送费吃掉水费额度」的成因")
    @TableField("REFUND_AMOUNT")
    private Long refundAmount;

    @Schema(description = "服务端计算依据快照(JSON)：策略码、数量边界、四元额度锚点、已用额度、单价来源。"
            + "出账即冻结，事后只读不重算——重算会随价目表漂移，与原扣款流水对不上")
    @TableField("CALC_SNAPSHOT")
    private String calcSnapshot;

    @Schema(description = "执行状态(1372)：1待执行 2执行中 3已完成 4可重试 5需人工对账 6已终止。"
            + "合法迁移边的唯一出处是 AfterSaleTransitions，CAS 的前态 IN 列表由它生成")
    @TableField("ACTION_STATUS")
    private Integer actionStatus;

    @Schema(description = "乐观锁版本：创建=1，每次状态迁移+1；是状态机 CAS 的前态条件之一")
    @TableField("VERSION")
    private Integer version;

    @Schema(description = "重试次数：仅基础设施类失败（锁等待/死锁）递增，达上限转「需人工对账」")
    @TableField("RETRY_COUNT")
    private Integer retryCount;

    @Schema(description = "下次可重试时间(yyyyMMddHHmmss)：Worker 与人工再执行的预筛条件，"
            + "认领可重试动作时要求 <= now")
    @TableField("NEXT_RETRY_TIME")
    private String nextRetryTime;

    @Schema(description = "支付机构退款单ID（包B）；卡内返还恒为 NULL")
    @TableField("REFUND_ID")
    private Long refundId;

    @Schema(description = "补送产生的子订单ID（包C）")
    @TableField("RESULT_ORDER_ID")
    private Long resultOrderId;

    @Schema(description = "补送产生的配送任务ID（包C）")
    @TableField("RESULT_TASK_ID")
    private Long resultTaskId;

    @Schema(description = "批准人（api_employee.ID）；用户自助取消为 NULL")
    @TableField("APPROVE_BY")
    private Long approveBy;

    @Schema(description = "批准时间(yyyyMMddHHmmss)")
    @TableField("APPROVE_TIME")
    private String approveTime;

    @Schema(description = "终态时间(yyyyMMddHHmmss)：完成/终止/转人工三者之一发生的时刻")
    @TableField("FINISH_TIME")
    private String finishTime;

    @Schema(description = "最近一次失败原因(max500)：转人工对账时的排查依据，"
            + "由独立事务写入（主事务已回滚，钱不能动但证据必须存活）")
    @TableField("LAST_ERROR")
    private String lastError;
}
