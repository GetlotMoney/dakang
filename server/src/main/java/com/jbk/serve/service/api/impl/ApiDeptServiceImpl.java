package com.jbk.serve.service.api.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.yulichang.base.MPJBaseService;
import com.github.yulichang.base.MPJBaseServiceImpl;
import com.github.yulichang.toolkit.MPJWrappers;
import com.google.common.collect.Lists;
import com.jbk.serve.service.api.IApiEmployeeService;
import com.jbk.tool.data.api.bo.ApiDeptBo;
import com.jbk.tool.data.api.po.ApiDept;
import com.jbk.serve.mapper.api.ApiDeptMapper;
import com.jbk.serve.service.api.IApiDeptService;
import com.jbk.tool.data.api.po.ApiEmployee;
import com.jbk.tool.data.api.vo.ApiDeptTreeVo;
import com.jbk.tool.data.api.vo.ApiDeptVo;
import com.jbk.tool.utils.OptionalUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.hibernate.validator.internal.util.stereotypes.Lazy;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
@Service
public class ApiDeptServiceImpl extends MPJBaseServiceImpl<ApiDeptMapper, ApiDept> implements IApiDeptService {

    @Autowired
    @Lazy
    private IApiEmployeeService employeeService;

    @Override
    public ApiDeptVo getData(Long id) {
        ApiDeptVo apiDeptVo = selectJoinOne(ApiDeptVo.class, MPJWrappers.lambdaJoin(ApiDept.class)
                .leftJoin(ApiEmployee.class, ApiEmployee::getId, ApiDept::getId)
                .eq(ApiDept::getId, id)
                .selectAsClass(ApiDept.class, ApiDeptVo.class)
                .selectAssociation(ApiEmployee.class, ApiDeptVo::getDeptManagerIdToEmployee)
        );
        OptionalUtils.nullToElseThrow(apiDeptVo, "部门信息不存在");
        return apiDeptVo;
    }

    @Override
    public List<ApiDeptTreeVo> treeData() {
        List<ApiDeptVo> apiDeptVoList = selectJoinList(ApiDeptVo.class, MPJWrappers.lambdaJoin(ApiDept.class)
                .leftJoin(ApiEmployee.class, ApiEmployee::getId, ApiDept::getId)
                .orderByDesc(ApiDept::getDeptSort)
                .selectAsClass(ApiDept.class, ApiDeptVo.class)
                .selectAssociation(ApiEmployee.class, ApiDeptVo::getDeptManagerIdToEmployee)
        );
        return this.buildTree(apiDeptVoList);
    }

    @Override
    public Long saveData(ApiDeptBo deptBo) {
        ApiDept apiDept = BeanUtil.copyProperties(deptBo, ApiDept.class);
        save(apiDept);
        return apiDept.getId();
    }

    @Override
    public Boolean deleteData(Long id) {
        long childDeptCount = count(Wrappers.lambdaQuery(ApiDept.class)
                .eq(ApiDept::getDeptParentId, id));
        OptionalUtils.gtZeroElseThrow(childDeptCount, "该部门下还有子部门，无法删除");
        long employeeCount = employeeService.count(Wrappers.lambdaQuery(ApiEmployee.class)
                .eq(ApiEmployee::getDeptId, id));
        OptionalUtils.gtZeroElseThrow(employeeCount, "该部门下还有人，无法删除");
        return removeById(id);
    }

    @Override
    public Boolean updateData(ApiDeptBo deptBo) {
        ApiDept apiDept = BeanUtil.copyProperties(deptBo, ApiDept.class);
        return updateById(apiDept);
    }

    /**
     * 构建部门树结构
     */
    private List<ApiDeptTreeVo> buildTree(List<ApiDeptVo> apiDeptVoList) {
        if (ObjectUtil.isEmpty(apiDeptVoList)) {
            return Lists.newArrayList();
        }
        List<ApiDeptVo> rootList = apiDeptVoList.stream()
                .filter(e -> e.getDeptParentId() == null || ObjectUtil.equals(e.getDeptParentId(), 0L))
                .collect(Collectors.toList());
        if (ObjectUtil.isEmpty(rootList)) {
            return Lists.newArrayList();
        }
        List<ApiDeptTreeVo> treeRootVoList = BeanUtil.copyToList(rootList, ApiDeptTreeVo.class);
        recursiveBuildTree(treeRootVoList, apiDeptVoList);
        return treeRootVoList;
    }

    /**
     * 构建所有根节点的下级树形结构
     * 返回值为层序遍历结果
     */
    private void recursiveBuildTree(List<ApiDeptTreeVo> treeRootVoList, List<ApiDeptVo> apiDeptVoList) {
        int nodeSize = treeRootVoList.size();
        for (int i = 0; i < nodeSize; i++) {
            ApiDeptTreeVo node = treeRootVoList.get(i);
            List<ApiDeptTreeVo> children = getChildren(node.getId(), apiDeptVoList);
            if (ObjectUtil.isNotEmpty(children)) {
                node.setChildren(children);
                recursiveBuildTree(children, apiDeptVoList);
            }
        }
    }

    /**
     * 获取子元素
     */
    private List<ApiDeptTreeVo> getChildren(Long deptId, List<ApiDeptVo> voList) {
        List<ApiDeptVo> childrenEntityList = voList.stream().filter(e -> deptId.equals(e.getDeptParentId())).collect(Collectors.toList());
        if (ObjectUtil.isEmpty(childrenEntityList)) {
            return Lists.newArrayList();
        }
        return BeanUtil.copyToList(childrenEntityList, ApiDeptTreeVo.class);
    }
}


