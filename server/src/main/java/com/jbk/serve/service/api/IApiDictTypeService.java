package com.jbk.serve.service.api;

import com.github.yulichang.base.MPJBaseService;
import com.jbk.tool.data.api.po.ApiDictType;
import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.validator.ValidArrayList;

import java.util.List;

/**
 * <p>
 * 字典类型表 服务类
 * </p>
 *
 * @author xs
 * @since 2025-09-04
 */
public interface IApiDictTypeService extends MPJBaseService<ApiDictType> {

    ApiDictType getByType(String dictType);

    List<ApiDictType> listByType(ValidArrayList<String> dictTypeList);
}


