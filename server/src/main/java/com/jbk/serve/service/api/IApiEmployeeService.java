package com.jbk.serve.service.api;

import com.github.yulichang.base.MPJBaseService;
import com.jbk.tool.data.api.bo.ApiEmployeeBo;
import com.jbk.tool.data.api.po.ApiEmployee;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.vo.ApiEmployeeInitPwdVo;
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

    /** 新增员工；返回一次性初始密码回执（明文仅此一次，库内只存哈希）。 */
    ApiEmployeeInitPwdVo saveData(ApiEmployeeBo employeeBo);

    List<ApiEmployeeVo> getDataList(List<Long> employeeIdList);

    Long updateData(ApiEmployeeBo employeeBo);

    Boolean deleteData(Long id);

    /** 重置为一次性强随机初始密码并强制下线；返回回执（明文仅此一次）。 */
    ApiEmployeeInitPwdVo resetPassword(Long id);

    /** 修改当前会话本人的密码；请求体 id 不作为身份来源。 */
    Boolean updatePassword(ApiEmployeeBo employeeBo);

    Boolean delPositionById(Long id);
}


