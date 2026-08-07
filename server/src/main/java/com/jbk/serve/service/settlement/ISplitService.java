package com.jbk.serve.service.settlement;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.consts.settlement.SettlementEnum;
import com.jbk.tool.data.settlement.po.WsSplitRecord;

/**
 * 分账服务（E2E-08 包B）。
 *
 * <p>口径（任务书二.1/2/3 冻结）：分账基数=消费订单金额（内部记账分润，Pay-Sim 环境
 * 不动真实资金）；比例取「订单创建时点生效版本」快照进 SPLIT_RATE_SNAP，变更不追溯；
 * 万分比整除余数恒归平台。</p>
 *
 * <p>两段式：{@link #enqueueForOrder} 在订单完成事务内按收款方拆行插「待分账」
 * （计算轻：一次配置查询+归属解析；重活与状态推进归 Worker）；
 * {@link com.jbk.serve.service.settlement.impl.SplitSettleWorker} 异步把 1→2 并触发收益入账。
 * 幂等：uk_split_order_receiver 库层唯一，重放/并发零重复。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
public interface ISplitService extends IService<WsSplitRecord> {

    /**
     * 订单完成挂点：按商品线解析收款方，逐行插「待分账」记录（与完成动作同事务，
     * 完成回滚分账行同灭）。撞唯一键静默跳过（同单重放属合法幂等路径）。
     *
     * @param orderId     订单ID
     * @param orderNo     订单号（仅用于备注与收益流水追溯快照）
     * @param amountFen   分账基数金额(分)；配送线为水费+配送费整单口径
     * @param productLine 商品线
     * @param ownerUserId 机主（站/设备归属解析结果，可空=该单无机主收款方）
     * @param courierUserId 配送员（仅配送线，可空）
     * @param orderCreateTime 订单创建时间（yyyyMMddHHmmss，比例版本选取基准）
     */
    void enqueueForOrder(Long orderId, String orderNo, long amountFen,
                         SettlementEnum.ProductLine productLine,
                         Long ownerUserId, Long courierUserId, String orderCreateTime);

    /**
     * 配送订单分线分账（D-419，2026-08-07 甲方确认：配送费与水费完全分开、各自为线）。
     *
     * <p>水费部分按 <b>WATER 线</b>比例（与取水订单同口径——水就是水）、配送费部分按
     * <b>DELIVERY 线</b>比例分别计算；每个收款方仍落<b>一行</b>（金额=两线份额之和，
     * {@code SPLIT_RATE_SNAP} 记分线明细 JSON），行合计恒等于整单（水费+配送费），
     * 对账不变式与既有唯一键 {@code uk_split_order_receiver} 都不变。行级分线模型
     * 由 V2（ws_split_plan/item/component）接线时交付，本方法是 V1 口径下的最小正确实现。</p>
     *
     * @param waterFen       水费基数(分)
     * @param deliveryFeeFen 配送费基数(分)
     */
    /** 冻结期阈值（D-421）：CREATE_TIME 不晚于该串的待分账行才可结算。 */
    String settleableCreateTimeThreshold();


    void enqueueForDeliveryOrder(Long orderId, String orderNo, long waterFen, long deliveryFeeFen,
                                 Long ownerUserId, Long courierUserId, String orderCreateTime);

    /**
     * 单行推进（Worker 逐行调用，独立事务）：只收 ID，事务内锁定读数据库当前行
     * （ID+DATA_STATUS=0 FOR UPDATE），冻结期判定与入账事实（收款人/金额/备注）全部
     * 取锁内 DB 行——不信任调用方任何携带值（D-421 R1：旧签名收整行对象可被伪造
     * createTime 穿透冻结期）。前态 CAS 1→2 + 非平台行收益入账同事务。
     * fail-closed：行不存在、状态非待分账、CREATE_TIME 缺失/非法、未满冻结期一律
     * false（时间事实不可信按未满期处理，绝不放行）。
     * 必须经 Spring 代理调用（接口方法而非 Worker 内部私有方法——自调用会绕过事务代理）。
     *
     * @return 是否真正发生推进（false=让行：并发输方/重放/冻结期内/行事实非法）
     */
    boolean settleOne(Long splitId);

    /**
     * 水单分账机主解析：设备归属优先、缺则站归属（E2E-06 双轨口径的收款人收敛——
     * 分账收款人必须唯一，双命中时设备归属更精确）。均无归属返回 null，该单无机主收款方。
     * 结算路径与人工核账 6→4 路径共用此解析，两路口径不许分叉。
     */
    Long resolveWaterOwner(Long deviceId, Long stationId);
}
