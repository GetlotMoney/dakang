package com.jbk.serve.service.api;

import com.github.yulichang.base.MPJBaseService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiEmployeeBo;
import com.jbk.tool.data.api.bo.ApiPositionBo;
import com.jbk.tool.data.api.po.ApiPosition;
import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;
import com.jbk.tool.data.api.vo.ApiPositionVo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
public interface IApiPositionService extends MPJBaseService<ApiPosition> {

    PageDataVo<ApiPositionVo> pageData(ApiPositionBo positionBo);

    Long saveData(ApiPositionBo positionBo);

    Boolean deleteData(Long id);

    ApiPositionVo getData(Long id);

    Boolean updateData(ApiPositionBo positionBo);

    Boolean saveEmployeePosition( ApiEmployeeBo apiEmployeeBo);

    List<ApiEmployeeVo> listEmployeesByPosition( Long id);

    Boolean delEmployeePosition( Long id);
}


