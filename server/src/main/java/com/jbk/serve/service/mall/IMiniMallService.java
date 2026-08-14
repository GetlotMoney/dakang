package com.jbk.serve.service.mall;

import com.jbk.tool.data.mall.bo.MallIdBo;
import com.jbk.tool.data.mall.bo.MiniMallHomeBo;
import com.jbk.tool.data.mall.vo.MiniMallHomeVo;
import com.jbk.tool.data.mall.vo.MiniMallProductDetailVo;

/**
 * 小程序商城只读服务（E2E-09 S1）：白名单出参；数据源异常 fail-closed，
 * 端上不回退 Mock。
 */
public interface IMiniMallService {

    /** 首页：启用分类 + 已上架商品卡（最低售价/有货布尔）。 */
    MiniMallHomeVo home(MiniMallHomeBo bo);

    /** 详情：已上架商品 + 启用 SKU；非上架商品按不存在处理。 */
    MiniMallProductDetailVo productDetail(MallIdBo bo);
}
