package com.jbk.serve.service.api.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseServiceImpl;
import com.github.yulichang.toolkit.MPJWrappers;
import com.google.common.collect.Lists;
import com.jbk.serve.service.api.IApiEmployeeService;
import com.jbk.serve.service.api.IApiTagService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiEmployeeTagBo;
import com.jbk.tool.data.api.po.ApiEmployeeTag;
import com.jbk.serve.mapper.api.ApiEmployeeTagMapper;
import com.jbk.serve.service.api.IApiEmployeeTagService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.tool.data.api.vo.ApiEmployeeTagVo;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;
import com.jbk.tool.utils.OptionalUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author xs
 * @since 2025-09-15
 */
@Service
public class ApiEmployeeTagServiceImpl extends MPJBaseServiceImpl<ApiEmployeeTagMapper, ApiEmployeeTag> implements IApiEmployeeTagService {

    @Autowired
    @Lazy
    private IApiEmployeeService apiEmployeeService;

    @Autowired
    @Lazy
    private IApiTagService apiTagService;

    @Override
    public Long saveData(ApiEmployeeTagBo employeeTagBo) {
        //先判断该员工和该员工标签是否已经存在
        LambdaQueryWrapper<ApiEmployeeTag> employeeTagLambdaQueryWrapper = new LambdaQueryWrapper<>();
        employeeTagLambdaQueryWrapper
                .eq(ApiEmployeeTag::getEmployeeId, employeeTagBo.getEmployeeId())
                .eq(ApiEmployeeTag::getTagId, employeeTagBo.getTagId());
        long count = count(employeeTagLambdaQueryWrapper);
        OptionalUtils.gtZeroElseThrow(count, "该员工已经关联了此标签");
        ApiEmployeeTag employeeTag = BeanUtil.copyProperties(employeeTagBo, ApiEmployeeTag.class);
        //保存关联
        save(employeeTag);
        return employeeTag.getId();
    }

    @Override
    public Boolean updateData(ApiEmployeeTagBo employeeTagBo) {
        // 检查关联信息是否存在
        ApiEmployeeTag existEmployeeTag = getById(employeeTagBo.getId());
        OptionalUtils.nullToElseThrow(existEmployeeTag, "员工标签关联信息不存在");

        // 判断此关联是否已经存在
        long count = count(Wrappers.lambdaQuery(ApiEmployeeTag.class)
                .eq(ApiEmployeeTag::getEmployeeId, employeeTagBo.getEmployeeId())
                .eq(ApiEmployeeTag::getTagId, employeeTagBo.getTagId())
                .ne(ApiEmployeeTag::getId, employeeTagBo.getId())
        );
        OptionalUtils.gtZeroElseThrow(count, "该员工已关联此标签，更新失败");

        // 更新关联
        ApiEmployeeTag employeeTag = BeanUtil.copyProperties(employeeTagBo, ApiEmployeeTag.class);
        return updateById(employeeTag);

    }

    @Override
    public Boolean deleteEmployeeTagData(ApiEmployeeTagVo employeeTagVo) {
        // 根据标签ID和员工ID查找关联记录
        LambdaQueryWrapper<ApiEmployeeTag> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ApiEmployeeTag::getTagId, employeeTagVo.getTagId())
                   .eq(ApiEmployeeTag::getEmployeeId, employeeTagVo.getEmployeeId());
        
        // 检查关联记录是否存在
        ApiEmployeeTag existRecord = getOne(queryWrapper);
        OptionalUtils.nullToElseThrow(existRecord, "该员工标签关联信息不存在");
        
        // 执行删除操作
        boolean result = remove(queryWrapper);
        
        return result;
    }

    @Override
    public PageDataVo<ApiEmployeeVo> pageEmployee(ApiEmployeeTagBo employeeTagBo) {
        // 人员信息
        Page<ApiEmployeeTag> page = page(new Page<>(employeeTagBo.getCurrent(), employeeTagBo.getSize()), Wrappers.lambdaQuery(ApiEmployeeTag.class)
                .eq(ApiEmployeeTag::getTagId, employeeTagBo.getTagId())
        );
        List<ApiEmployeeTag> etList = page.getRecords();
        if(ObjectUtil.isEmpty(etList)){
            return PageDataVo.getPageData(Lists.newArrayList(),page.getTotal());
        }
        List<Long> employeeIdList = etList.stream().map(ApiEmployeeTag::getEmployeeId).collect(Collectors.toList());
        // 查询用户信息
        List<ApiEmployeeVo> emList = apiEmployeeService.getDataList(employeeIdList);
        return PageDataVo.getPageData(emList,page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateOrInsertData(Long id, List<Long> tagIdList) {
        List<Long> oldTagList = list(Wrappers.lambdaQuery(ApiEmployeeTag.class)
                .eq(ApiEmployeeTag::getEmployeeId, id)
        ).stream().map(ApiEmployeeTag::getTagId).collect(Collectors.toList());

        List<Long> removeList = Lists.newArrayList(oldTagList);
        removeList.removeAll(tagIdList);
        if(ObjectUtil.isNotEmpty(removeList)){
            remove(Wrappers.lambdaQuery(ApiEmployeeTag.class)
                    .eq(ApiEmployeeTag::getEmployeeId, id)
                    .in(ApiEmployeeTag::getTagId,removeList)
            );
        }
        List<Long> addList = Lists.newArrayList(tagIdList);
        addList.removeAll(oldTagList);
        if(ObjectUtil.isNotEmpty(addList)){
            List<ApiEmployeeTag> employeeTagList = addList.stream()
                    .map(tagId -> {
                        ApiEmployeeTag employeeTag = new ApiEmployeeTag();
                        employeeTag.setEmployeeId(id);
                        employeeTag.setTagId(tagId);
                        return employeeTag;
                    })
                    .collect(Collectors.toList());
            saveBatch(employeeTagList);
        }

        return Boolean.TRUE;
    }


}


