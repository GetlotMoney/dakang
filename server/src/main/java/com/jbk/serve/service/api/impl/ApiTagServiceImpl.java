package com.jbk.serve.service.api.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.common.collect.Lists;
import com.jbk.serve.service.api.IApiEmployeeTagService;
import com.jbk.tool.data.api.po.ApiEmployeeTag;
import com.jbk.tool.data.api.bo.ApiTagBo;
import com.jbk.tool.data.api.po.ApiTag;
import com.jbk.serve.mapper.api.ApiTagMapper;
import com.jbk.serve.service.api.IApiTagService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.tool.data.api.vo.ApiTagVo;
import com.jbk.tool.utils.OptionalUtils;
import org.hibernate.validator.internal.util.stereotypes.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author xs
 * @since 2025-09-15
 */
@Service
public class ApiTagServiceImpl extends ServiceImpl<ApiTagMapper, ApiTag> implements IApiTagService {

    @Autowired
    @Lazy
    private IApiEmployeeTagService apiEmployeeTagService;

    @Override
    public ApiTagVo getData(Long id) {
        ApiTag tag = this.getById(id);
        OptionalUtils.nullToElseThrow(tag,"标签信息不存在");
        ApiTagVo tagVo = BeanUtil.copyProperties(tag, ApiTagVo.class);
        return tagVo;
    }

    @Override
    public Boolean updateData(ApiTagBo tagBo) {
        ApiTag apiTag = BeanUtil.copyProperties(tagBo, ApiTag.class);
        updateById(apiTag);
        return Boolean.TRUE;
    }

    @Override
    public Long saveData(ApiTagBo tagBo) {
        ApiTag apiTag = BeanUtil.copyProperties(tagBo, ApiTag.class);
        save(apiTag);
        return apiTag.getId();
    }

    @Override
    public Boolean deleteData(Long id) {
        // 先根据tagId查询tag信息是否存在
        ApiTag tag = this.getById(id);
        OptionalUtils.nullToElseThrow(tag, "标签信息不存在");
        
        // 根据tagId去employeeTag关联表中查询是否存在对应关联的用户
        long employeeCount = apiEmployeeTagService.count(Wrappers.lambdaQuery(ApiEmployeeTag.class)
                .eq(ApiEmployeeTag::getTagId, id));
        
        // employeeTag表中存在对应的用户则无法删除
        OptionalUtils.gtZeroElseThrow(employeeCount, "该标签下还有关联的员工，无法删除");
        
        // 不存在关联用户则执行删除
        boolean result = removeById(id);
        return result;
    }

    @Override
    public List<ApiTagVo> listData(String tagName) {
        List<ApiTag> tagList = list(Wrappers.lambdaQuery(ApiTag.class)
                .like(StrUtil.isNotEmpty(tagName), ApiTag::getTagName, tagName)
                .orderByDesc(ApiTag::getId)
        );
        if(ObjectUtil.isEmpty(tagList)){
            return Lists.newArrayList();
        }
        return BeanUtil.copyToList(tagList,ApiTagVo.class);
    }
}


