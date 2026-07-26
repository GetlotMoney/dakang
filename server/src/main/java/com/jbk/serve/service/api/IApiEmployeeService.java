package com.jbk.serve.service.api;

import com.github.yulichang.base.MPJBaseService;
import com.jbk.tool.data.api.bo.ApiEmployeeBo;
import com.jbk.tool.data.api.po.ApiEmployee;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
public interface IApiEmployeeService extends MPJBaseService<ApiEmployee> {

    PageDataVo<ApiEmployeeVo> getPage(ApiEmployeeBo employeeBo);

    ApiEmployeeVo getData(Long id);

    Long saveData(ApiEmployeeBo employeeBo);

    List<ApiEmployeeVo> getDataList(List<Long> employeeIdList);

    Long updateData(ApiEmployeeBo employeeBo);

    Boolean deleteData(Long id);

    Boolean resetPassword(Long id);

    Boolean updatePassword(ApiEmployeeBo employeeBo);

    Boolean delPositionById(Long id);
}


