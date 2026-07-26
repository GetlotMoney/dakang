package com.jbk.serve.service.product;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.product.bo.WsWaterTypeBo;
import com.jbk.tool.data.product.po.WsWaterType;
import com.jbk.tool.data.product.vo.WsWaterTypeVo;

import java.util.List;

/**
 * 水种字典服务（REQ-073：启停/排序/默认水种统一维护，出水口与套餐只引用不定义）
 *
 * @author dakang
 * @since 2026-07-12
 */
public interface IWsWaterTypeService extends IService<WsWaterType> {

    PageDataVo<WsWaterTypeVo> pageData(WsWaterTypeBo waterTypeBo);

    /** 全部启用水种（出水口/套餐配置下拉，按排序升序） */
    List<WsWaterTypeVo> listEnabled();

    Long saveData(WsWaterTypeBo waterTypeBo);

    Boolean updateData(WsWaterTypeBo waterTypeBo);

    /** 删除前置：已禁用 且 无出水口引用 */
    Boolean deleteData(Long id);
}
