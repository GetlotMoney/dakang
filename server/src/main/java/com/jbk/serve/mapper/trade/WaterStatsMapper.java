package com.jbk.serve.mapper.trade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 按水种业务用量统计（S5）：只聚合现有订单与配送任务事实，不建第二套业务账本。
 *
 * <p>口径（任务书 8.1）：取水完成量只算已完成(4)；超量异常(6)单列计数不入完成量；
 * 取消(5)不计入；不足退差=完成单的 计划−实际（正差），不改写实际出水量事实。
 * 配送桶数只算已签收(5)任务；补送单（售后 RESEND 零金额子单，配送域唯一零元来源）
 * 单列，不与原销售订单重复累计。水种维度：取水线自出水口档案名称、配送线自任务
 * 快照名称（8 种水最终定义未落，名称即现有事实）。时间为服务端闭区间。</p>
 *
 * @author dakang
 * @since 2026-08-07
 */
@Mapper
public interface WaterStatsMapper {

    /** 取水线聚合行。 */
    class WaterLineRow {
        private String waterTypeName;
        private Long planMl;
        private Long actualMl;
        private Long shortfallMl;
        private Long abnormalCount;

        public String getWaterTypeName() {
            return waterTypeName;
        }

        public void setWaterTypeName(String waterTypeName) {
            this.waterTypeName = waterTypeName;
        }

        public Long getPlanMl() {
            return planMl;
        }

        public void setPlanMl(Long planMl) {
            this.planMl = planMl;
        }

        public Long getActualMl() {
            return actualMl;
        }

        public void setActualMl(Long actualMl) {
            this.actualMl = actualMl;
        }

        public Long getShortfallMl() {
            return shortfallMl;
        }

        public void setShortfallMl(Long shortfallMl) {
            this.shortfallMl = shortfallMl;
        }

        public Long getAbnormalCount() {
            return abnormalCount;
        }

        public void setAbnormalCount(Long abnormalCount) {
            this.abnormalCount = abnormalCount;
        }
    }

    /** 配送线聚合行（按水种×容器规格分组；毫升换算在 Java 用 DeliveryPricing 单源完成）。 */
    class DeliveryLineRow {
        private String waterTypeName;
        private String containerSpec;
        private Long saleBuckets;
        private Long resendBuckets;

        public String getWaterTypeName() {
            return waterTypeName;
        }

        public void setWaterTypeName(String waterTypeName) {
            this.waterTypeName = waterTypeName;
        }

        public String getContainerSpec() {
            return containerSpec;
        }

        public void setContainerSpec(String containerSpec) {
            this.containerSpec = containerSpec;
        }

        public Long getSaleBuckets() {
            return saleBuckets;
        }

        public void setSaleBuckets(Long saleBuckets) {
            this.saleBuckets = saleBuckets;
        }

        public Long getResendBuckets() {
            return resendBuckets;
        }

        public void setResendBuckets(Long resendBuckets) {
            this.resendBuckets = resendBuckets;
        }
    }

    @Select("""
            SELECT ot.WATER_TYPE AS waterTypeName,
              SUM(CASE WHEN o.ORDER_STATUS = 4 THEN COALESCE(o.PLAN_ML, 0) ELSE 0 END) AS planMl,
              SUM(CASE WHEN o.ORDER_STATUS = 4 THEN COALESCE(o.ACTUAL_ML, 0) ELSE 0 END) AS actualMl,
              SUM(CASE WHEN o.ORDER_STATUS = 4 AND COALESCE(o.ACTUAL_ML, 0) < COALESCE(o.PLAN_ML, 0)
                   THEN COALESCE(o.PLAN_ML, 0) - COALESCE(o.ACTUAL_ML, 0) ELSE 0 END) AS shortfallMl,
              SUM(CASE WHEN o.ORDER_STATUS = 6 THEN 1 ELSE 0 END) AS abnormalCount
            FROM ws_order o
            JOIN ws_device_outlet ot ON ot.ID = o.OUTLET_ID AND ot.DATA_STATUS = 0
            WHERE o.ORDER_TYPE = 1 AND o.DATA_STATUS = 0
              AND o.CREATE_TIME >= #{startTime} AND o.CREATE_TIME <= #{endTime}
              AND (#{stationId} IS NULL OR o.STATION_ID = #{stationId})
            GROUP BY ot.WATER_TYPE
            """)
    List<WaterLineRow> aggregateWaterLine(@Param("startTime") String startTime,
                                          @Param("endTime") String endTime,
                                          @Param("stationId") Long stationId);

    @Select("""
            SELECT t.WATER_TYPE AS waterTypeName, t.CONTAINER_SPEC AS containerSpec,
              SUM(CASE WHEN o.ORDER_AMOUNT > 0
                   THEN COALESCE(t.ACTUAL_DELIVERY_COUNT, t.DELIVERY_COUNT) ELSE 0 END) AS saleBuckets,
              SUM(CASE WHEN o.ORDER_AMOUNT = 0
                   THEN COALESCE(t.ACTUAL_DELIVERY_COUNT, t.DELIVERY_COUNT) ELSE 0 END) AS resendBuckets
            FROM ws_delivery_task t
            JOIN ws_order o ON o.ID = t.ORDER_ID AND o.DATA_STATUS = 0
            WHERE t.TASK_STATUS = 5 AND t.DATA_STATUS = 0
              AND t.CREATE_TIME >= #{startTime} AND t.CREATE_TIME <= #{endTime}
              AND (#{stationId} IS NULL OR t.STATION_ID = #{stationId})
            GROUP BY t.WATER_TYPE, t.CONTAINER_SPEC
            """)
    List<DeliveryLineRow> aggregateDeliveryLine(@Param("startTime") String startTime,
                                                @Param("endTime") String endTime,
                                                @Param("stationId") Long stationId);
}
