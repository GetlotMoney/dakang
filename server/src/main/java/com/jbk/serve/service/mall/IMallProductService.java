package com.jbk.serve.service.mall;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallIdBo;
import com.jbk.tool.data.mall.bo.MallProductBo;
import com.jbk.tool.data.mall.bo.MallQueryBo;
import com.jbk.tool.data.mall.bo.MallShelfBo;
import com.jbk.tool.data.mall.vo.MallProductDetailVo;
import com.jbk.tool.data.mall.vo.MallProductVo;

/**
 * 商城商品（SPU+SKU）服务（E2E-09 S1）。
 */
public interface IMallProductService {

    PageDataVo<MallProductVo> page(MallQueryBo bo);

    MallProductDetailVo detail(MallIdBo bo);

    /** 新建 SPU+SKU（同事务原子；至少一条 SKU）。 */
    Long save(MallProductBo bo);

    /** 更新 SPU 字段并 upsert SKU（编号不可改、不物理删除 SKU）。 */
    boolean update(MallProductBo bo);

    /** 上架（VERSION CAS）：三闸=分类启用、存在启用 SKU、存在启用仓可售库存。 */
    boolean publish(MallShelfBo bo);

    /** 下架（VERSION CAS）：不删 SKU/库存/流水。 */
    boolean unpublish(MallShelfBo bo);
}
