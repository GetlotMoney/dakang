package com.jbk.serve.service.api;

import com.github.yulichang.base.MPJBaseService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiEmployeeTagBo;
import com.jbk.tool.data.api.po.ApiEmployeeTag;
import com.jbk.tool.data.api.vo.ApiEmployeeTagVo;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author xs
 * @since 2025-09-15
 */
public interface IApiEmployeeTagService extends MPJBaseService<ApiEmployeeTag> {

    Long saveData(ApiEmployeeTagBo employeeTagBo);

    Boolean updateData(ApiEmployeeTagBo employeeTagBo);

    Boolean deleteEmployeeTagData(ApiEmployeeTagVo employeeTagVo);

    PageDataVo<ApiEmployeeVo> pageEmployee(ApiEmployeeTagBo employeeTagBo);

    Boolean updateOrInsertData(Long id, List<Long> tagIdList);
}


