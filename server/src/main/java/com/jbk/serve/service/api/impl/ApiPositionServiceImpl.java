package com.jbk.serve.service.api.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseServiceImpl;
import com.github.yulichang.toolkit.MPJWrappers;
import com.jbk.serve.service.api.IApiEmployeeService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiEmployeeBo;
import com.jbk.tool.data.api.bo.ApiPositionBo;
import com.jbk.tool.data.api.po.ApiEmployee;
import com.jbk.tool.data.api.po.ApiEmployeeTag;
import com.jbk.tool.data.api.po.ApiPosition;
import com.jbk.serve.mapper.api.ApiPositionMapper;
import com.jbk.serve.service.api.IApiPositionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.tool.data.api.po.ApiTag;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;
import com.jbk.tool.data.api.vo.ApiPositionVo;
import com.jbk.tool.utils.OptionalUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
@Service
public class ApiPositionServiceImpl extends MPJBaseServiceImpl<ApiPositionMapper, ApiPosition> implements IApiPositionService {

    @Autowired
    @Lazy
    private IApiEmployeeService employeeService;

    @Override
    public PageDataVo<ApiPositionVo> pageData(ApiPositionBo positionBo) {
        Page<ApiPositionVo> positionVoPage = selectJoinListPage(
                new Page<>(positionBo.getCurrent(), positionBo.getSize()),
                ApiPositionVo.class,
                MPJWrappers.lambdaJoin(ApiPosition.class)
                        .and(StrUtil.isNotEmpty(positionBo.getPositionName()), e -> {
                                    e.like(ApiPosition::getPositionName, positionBo.getPositionName())
                                            .or()
                                            .like(ApiPosition::getPositionLevel, positionBo.getPositionName());
                                }
                        )
                        .orderByAsc(ApiPosition::getPositionSort)
                        .selectAsClass(ApiPosition.class, ApiPositionVo.class)
        );
        return PageDataVo.getPageData(positionVoPage);
    }

    @Override
    public Long saveData(ApiPositionBo positionBo) {
        ApiPosition apiPosition = BeanUtil.copyProperties(positionBo, ApiPosition.class);
        save(apiPosition);
        return apiPosition.getId();
    }

    @Override
    public Boolean deleteData(Long id) {
        long count = employeeService.count(Wrappers.lambdaQuery(ApiEmployee.class)
                .eq(ApiEmployee::getPositionId, id)
        );
        OptionalUtils.gtZeroElseThrow(count, "存在人员为该职位，无法删除");
        removeById(id);
        return Boolean.TRUE;
    }

    @Override
    public ApiPositionVo getData(Long id) {
        ApiPosition position = getById(id);
        OptionalUtils.nullToElseThrow(position, "职务信息不存在");
        ApiPositionVo positionVo = BeanUtil.copyProperties(position, ApiPositionVo.class);
        return positionVo;
    }

    @Override
    public Boolean updateData(ApiPositionBo positionBo) {
        ApiPosition apiPosition = BeanUtil.copyProperties(positionBo, ApiPosition.class);
        updateById(apiPosition);
        return Boolean.TRUE;
    }

    @Override
    public Boolean saveEmployeePosition(ApiEmployeeBo apiEmployeeBo) {
        ApiPosition position = getById(apiEmployeeBo.getPositionId());
        OptionalUtils.nullToElseThrow(position, "职务信息不存在");
        ApiEmployeeVo employee = employeeService.getData(apiEmployeeBo.getId());
        employee.setPositionId(apiEmployeeBo.getPositionId());
        ApiEmployeeBo employeeBo = BeanUtil.copyProperties(employee, ApiEmployeeBo.class);
        employeeService.updateData(employeeBo);
        return Boolean.TRUE;
    }

    @Override
    public List<ApiEmployeeVo> listEmployeesByPosition(Long id) {
        List<ApiEmployeeVo> listEmployee = employeeService.selectJoinList(ApiEmployeeVo.class,
                MPJWrappers.lambdaJoin(ApiEmployee.class)
                        .leftJoin(ApiEmployeeTag.class,ApiEmployeeTag::getEmployeeId,ApiEmployee::getId)
                        .leftJoin(ApiTag.class,ApiTag::getId,ApiEmployeeTag::getTagId)
                        .eq(ApiEmployee::getPositionId,id)
                        .selectAsClass(ApiEmployee.class, ApiEmployeeVo.class)
                        .selectCollection(ApiTag.class,ApiEmployeeVo::getTagList)
        );
        return listEmployee;
    }

    @Override
    public Boolean delEmployeePosition(Long id) {
        employeeService.delPositionById(id);
        return Boolean.TRUE;
    }
}


