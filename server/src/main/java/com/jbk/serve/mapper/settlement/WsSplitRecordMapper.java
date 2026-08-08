package com.jbk.serve.mapper.settlement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.settlement.po.WsSplitRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * WsSplitRecord Mapper（E2E-08 包B）。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Mapper
public interface WsSplitRecordMapper extends BaseMapper<WsSplitRecord> {

    /**
     * 结算入口锁定读（D-421 R1）：settleOne 的冻结判定与入账事实必须取自锁内数据库
     * 当前行，不得信任调用方携带的对象。行不存在/已逻辑删返回 null，调用方 fail-closed。
     */
    @Select("SELECT * FROM ws_split_record WHERE ID = #{splitId} AND DATA_STATUS = 0 FOR UPDATE")
    WsSplitRecord selectByIdForUpdate(Long splitId);

    /**
     * 冲减入口锁定读（D-420 S1）：按订单锁全部分账行（ORDER BY ID 固定锁序，防对向死锁），
     * 冲减判定与份额计算全取锁内 DB 行——与结算 Worker 的 settleOne 行锁互斥串行，
     * 保证「冲减与结算并发」时同一行的状态推进恒有先后。
     */
    @Select("SELECT * FROM ws_split_record WHERE ORDER_ID = #{orderId} AND DATA_STATUS = 0 ORDER BY ID FOR UPDATE")
    java.util.List<WsSplitRecord> lockByOrderIdForUpdate(Long orderId);

    /**
     * 钱包在途分润聚合（D-421/D-420 R1）：SUM/MIN 下沉库层，不把全部行拉进 JVM。
     * SPLIT_STATUS=1 即待分账；口径为<b>净额</b>=GREATEST(SPLIT_AMOUNT-REVERSED_AMOUNT,0)——
     * 全额冲减的待分账行不再显示在途、部分冲减后展示净额而非毛额；零净额行同样
     * 不参与 MIN(CREATE_TIME)。无 GROUP BY 的聚合恒返回一行。
     */
    @Select("SELECT COALESCE(SUM(GREATEST(SPLIT_AMOUNT - REVERSED_AMOUNT, 0)), 0) AS pendingFen,"
            + " MIN(CREATE_TIME) AS earliestCreateTime"
            + " FROM ws_split_record WHERE RECEIVER_USER_ID = #{userId} AND SPLIT_STATUS = 1"
            + " AND SPLIT_AMOUNT - REVERSED_AMOUNT > 0 AND DATA_STATUS = 0")
    PendingSplitAgg aggregatePendingByReceiver(Long userId);

    /** 在途分润聚合结果（仅本 Mapper 出参）。 */
    class PendingSplitAgg {
        private Long pendingFen;
        private String earliestCreateTime;

        public Long getPendingFen() {
            return pendingFen;
        }

        public void setPendingFen(Long pendingFen) {
            this.pendingFen = pendingFen;
        }

        public String getEarliestCreateTime() {
            return earliestCreateTime;
        }

        public void setEarliestCreateTime(String earliestCreateTime) {
            this.earliestCreateTime = earliestCreateTime;
        }
    }
}
