package com.jbk.serve.service.mall;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallCategoryBo;
import com.jbk.tool.data.mall.bo.MallQueryBo;
import com.jbk.tool.data.mall.bo.MallStatusChangeBo;
import com.jbk.tool.data.mall.vo.MallCategoryVo;

import java.util.List;

/**
 * 商城分类服务（E2E-09 S1）。
 */
public interface IMallCategoryService {

    PageDataVo<MallCategoryVo> page(MallQueryBo bo);

    /** 下拉用：全部未删除分类（含停用，管理端筛选需要）。 */
    List<MallCategoryVo> listAll();

    Long save(MallCategoryBo bo);

    boolean update(MallCategoryBo bo);

    /** 启停：停用分类下的商品不得新上架（上架闸在商品服务）。 */
    boolean changeStatus(MallStatusChangeBo bo);
}
