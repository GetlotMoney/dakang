package com.jbk.serve.mapper.mall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.mall.po.WsMallShipmentItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商城出库包裹明细 Mapper（E2E-09 L1）。
 *
 * @author dakang
 * @since 2026-08-11
 */
@Mapper
public interface WsMallShipmentItemMapper extends BaseMapper<WsMallShipmentItem> {

    /** 按包裹读明细，按 SKU 升序——与库存动作的锁序同向，避免两处顺序不一致造成死锁。 */
    @Select("SELECT * FROM ws_mall_shipment_item WHERE SHIPMENT_ID = #{shipmentId} ORDER BY SKU_ID")
    List<WsMallShipmentItem> selectByShipmentOrderBySku(@Param("shipmentId") Long shipmentId);
}
