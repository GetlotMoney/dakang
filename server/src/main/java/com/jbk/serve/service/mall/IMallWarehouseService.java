package com.jbk.serve.service.mall;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallIdBo;
import com.jbk.tool.data.mall.bo.MallQueryBo;
import com.jbk.tool.data.mall.bo.MallStatusChangeBo;
import com.jbk.tool.data.mall.bo.MallWarehouseBo;
import com.jbk.tool.data.mall.vo.MallWarehouseVo;

import java.util.List;

/**
 * 商城前置仓服务（E2E-09 S1）：范围 JSON 服务端唯一构造与校验；电话恒脱敏出参。
 */
public interface IMallWarehouseService {

    PageDataVo<MallWarehouseVo> page(MallQueryBo bo);

    MallWarehouseVo detail(MallIdBo bo);

    /** 下拉用：全部未删除仓（含停用，库存页筛选需要）。 */
    List<MallWarehouseVo> listAll();

    Long save(MallWarehouseBo bo);

    boolean update(MallWarehouseBo bo);

    /** 启停（VERSION CAS）：停用仓不参与小程序可售聚合。 */
    boolean changeStatus(MallStatusChangeBo bo);
}
