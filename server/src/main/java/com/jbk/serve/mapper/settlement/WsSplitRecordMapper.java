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
     * 钱包在途分润聚合（D-421 R1-P2）：SUM/MIN 下沉库层，不把全部行拉进 JVM。
     * SPLIT_STATUS=1 即待分账（SettlementEnum.SplitStatus.PENDING）；只聚合
     * SPLIT_AMOUNT&gt;0——零元行（比例 0/整除归零）不计在途金额、不许提前最早解冻时间。
     * 无 GROUP BY 的聚合恒返回一行（无在途时 pendingFen=0、earliestCreateTime=null）。
     */
    @Select("SELECT COALESCE(SUM(SPLIT_AMOUNT), 0) AS pendingFen, MIN(CREATE_TIME) AS earliestCreateTime"
            + " FROM ws_split_record WHERE RECEIVER_USER_ID = #{userId} AND SPLIT_STATUS = 1"
            + " AND SPLIT_AMOUNT > 0 AND DATA_STATUS = 0")
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
