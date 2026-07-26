package com.jbk.serve.service.api.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.yulichang.base.MPJBaseServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.github.yulichang.toolkit.MPJWrappers;
import com.google.common.collect.Lists;
import com.jbk.serve.service.api.IApiLogLoginService;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiEmployeeBo;
import com.jbk.tool.data.api.po.*;
import com.jbk.serve.mapper.api.ApiEmployeeMapper;
import com.jbk.serve.service.api.IApiEmployeeService;
import com.jbk.serve.service.api.IApiEmployeeTagService;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.OptionalUtils;
import com.jbk.tool.utils.RSAUtils;
import com.jbk.tool.utils.SortUtils;
import com.jbk.tool.utils.auth.LogLoginUtils;
import com.jbk.tool.utils.satoken.StpKit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
@Service
public class ApiEmployeeServiceImpl extends MPJBaseServiceImpl<ApiEmployeeMapper, ApiEmployee> implements IApiEmployeeService {

    @Autowired
    private IApiEmployeeTagService apiEmployeeTagService;

    @Autowired
    private IApiLogLoginService logLoginService;

    @Override
    public ApiEmployeeVo getData(Long id) {
        ApiEmployeeVo employeeVo = selectJoinOne(ApiEmployeeVo.class, MPJWrappers.lambdaJoin(ApiEmployee.class)
                .leftJoin(ApiEmployeeTag.class, ApiEmployeeTag::getEmployeeId, ApiEmployee::getId)
                .leftJoin(ApiTag.class, ApiTag::getId, ApiEmployeeTag::getTagId)
                .leftJoin(ApiDept.class, ApiDept::getId, ApiEmployee::getDeptId)
                .leftJoin(ApiPosition.class, ApiPosition::getId, ApiEmployee::getPositionId)
                .eq(ApiEmployee::getId, id)
                .selectAsClass(ApiEmployee.class, ApiEmployeeVo.class)
                .selectCollection(ApiTag.class, ApiEmployeeVo::getTagList)
                .selectAssociation(ApiDept.class, ApiEmployeeVo::getDeptIdVo)
                .selectAssociation(ApiPosition.class, ApiEmployeeVo::getPositionIdVo)
        );
        OptionalUtils.nullToElseThrow(employeeVo, "员工信息不存在");
        return employeeVo;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveData(ApiEmployeeBo employeeBo) {
        // 唯一校验
        long count = count(Wrappers.lambdaQuery(ApiEmployee.class)
                .eq(ApiEmployee::getEmployeePhone, employeeBo.getEmployeePhone())
                .or()
                .eq(ApiEmployee::getLoginName, employeeBo.getLoginName())
        );
        OptionalUtils.gtZeroElseThrow(count, "用户手机号或登录名已存在，添加失败");
        // 保存员工
        ApiEmployee apiEmployee = BeanUtil.copyProperties(employeeBo, ApiEmployee.class);
        String phone = employeeBo.getEmployeePhone().substring(5);
        apiEmployee.setLoginPwd(RSAUtils.encrypt(phone));
        save(apiEmployee);
        // 保存标签
        if (ObjectUtil.isNotEmpty(employeeBo.getTagIdList())) {
            List<ApiEmployeeTag> employeeTagList = employeeBo.getTagIdList().stream()
                    .map(tagId -> {
                        ApiEmployeeTag employeeTag = new ApiEmployeeTag();
                        employeeTag.setEmployeeId(apiEmployee.getId());
                        employeeTag.setTagId(tagId);
                        return employeeTag;
                    })
                    .collect(Collectors.toList());
            apiEmployeeTagService.saveBatch(employeeTagList);
        }

        return apiEmployee.getId();
    }

    @Override
    public List<ApiEmployeeVo> getDataList(List<Long> employeeIdList) {
        if (ObjectUtil.isEmpty(employeeIdList)) {
            return Lists.newArrayList();
        }
        List<ApiEmployeeVo> employeeVoList = selectJoinList(ApiEmployeeVo.class, MPJWrappers.lambdaJoin(ApiEmployee.class)
                .leftJoin(ApiDept.class, ApiDept::getId, ApiEmployee::getDeptId)
                .leftJoin(ApiEmployeeTag.class, ApiEmployeeTag::getEmployeeId, ApiEmployee::getId)
                .leftJoin(ApiTag.class, ApiTag::getId, ApiEmployeeTag::getTagId)
                .leftJoin(ApiPosition.class, ApiPosition::getId, ApiEmployee::getPositionId)
                .in(ApiEmployee::getId, employeeIdList)
                .selectAsClass(ApiEmployee.class, ApiEmployeeVo.class)
                .selectAssociation(ApiDept.class, ApiEmployeeVo::getDeptIdVo)
                .selectCollection(ApiTag.class, ApiEmployeeVo::getTagList)
                .selectAssociation(ApiPosition.class, ApiEmployeeVo::getPositionIdVo)
        );
        return employeeVoList;
    }

    @Override
    public PageDataVo<ApiEmployeeVo> getPage(ApiEmployeeBo employeeBo) {
        // 查询用户id
        MPJLambdaWrapper<ApiEmployee> wrapper = MPJWrappers.lambdaJoin(ApiEmployee.class)
                .and(StrUtil.isNotEmpty(employeeBo.getEmployeeName()), e -> {
                    e.likeRight(ApiEmployee::getEmployeeName, employeeBo.getEmployeeName()).or()
                            .likeRight(ApiEmployee::getLoginName, employeeBo.getEmployeeName()).or()
                            .likeRight(ApiEmployee::getEmployeePhone, employeeBo.getEmployeeName());
                })
                .eq(ObjectUtil.isNotNull(employeeBo.getDeptId()), ApiEmployee::getDeptId, employeeBo.getDeptId())
                .eq(ObjectUtil.isNotNull(employeeBo.getDisabledFlag()), ApiEmployee::getDisabledFlag, employeeBo.getDisabledFlag())
                .eq(ObjectUtil.isNotNull(employeeBo.getPositionId()), ApiEmployee::getPositionId, employeeBo.getPositionId())
                .orderByDesc(ApiEmployee::getId)
                .selectAs(ApiEmployee::getId, ApiEmployeeVo::getId);
        if (ObjectUtil.isNotNull(employeeBo.getTagId())) {
            wrapper.innerJoin(ApiEmployeeTag.class, on ->
                    on.eq(ApiEmployeeTag::getEmployeeId, ApiEmployee::getId)
                            .eq(ApiEmployeeTag::getTagId, employeeBo.getTagId())
            );
        }
        Page<ApiEmployee> employeeIdPage = selectJoinListPage(new Page<>(employeeBo.getCurrent(), employeeBo.getSize()),
                ApiEmployee.class,
                wrapper);
        List<ApiEmployee> records = employeeIdPage.getRecords();
        if (ObjectUtil.isEmpty(records)) {
            return PageDataVo.getPageData(Lists.newArrayList(), employeeIdPage.getTotal());
        }
        List<Long> employeeIdList = records.stream().map(ApiEmployee::getId).collect(Collectors.toList());
        // 查询用户详情
        List<ApiEmployeeVo> employeeVoList = SortUtils.sortById(
                employeeIdList,
                selectJoinList(ApiEmployeeVo.class,
                        MPJWrappers.lambdaJoin(ApiEmployee.class)
                                .leftJoin(ApiEmployeeTag.class, ApiEmployeeTag::getEmployeeId, ApiEmployee::getId)
                                .leftJoin(ApiTag.class, ApiTag::getId, ApiEmployeeTag::getTagId)
                                .leftJoin(ApiDept.class, ApiDept::getId, ApiEmployee::getDeptId)
                                .leftJoin(ApiPosition.class, ApiPosition::getId, ApiEmployee::getPositionId)
                                .in(ApiEmployee::getId, employeeIdList)
                                .selectAsClass(ApiEmployee.class, ApiEmployeeVo.class)
                                .selectCollection(ApiTag.class, ApiEmployeeVo::getTagList)
                                .selectAssociation(ApiDept.class, ApiEmployeeVo::getDeptIdVo)
                                .selectAssociation(ApiPosition.class, ApiEmployeeVo::getPositionIdVo)),
                ApiEmployeeVo::getId
        );
        return PageDataVo.getPageData(employeeVoList, employeeIdPage.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long updateData(ApiEmployeeBo employeeBo) {
        // 唯一校验
        long count = count(Wrappers.lambdaQuery(ApiEmployee.class)
                .ne(ApiEmployee::getId, employeeBo.getId())
                .and(wrapper -> wrapper.eq(ApiEmployee::getEmployeePhone, employeeBo.getEmployeePhone())
                        .or()
                        .eq(ApiEmployee::getLoginName, employeeBo.getLoginName()))
        );
        OptionalUtils.gtZeroElseThrow(count, "用户手机号或登录名已存在，修改失败");
        // 更新员工
        ApiEmployee apiEmployee = BeanUtil.copyProperties(employeeBo, ApiEmployee.class);
        updateById(apiEmployee);
        // tag更新
        apiEmployeeTagService.updateOrInsertData(employeeBo.getId(),employeeBo.getTagIdList());
        return apiEmployee.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteData(Long id) {
        throw new JbkException("暂不支持删除用户");
    }

    @Override
    public Boolean resetPassword(Long id) {
        ApiEmployeeVo employeeVo = getData(id);
        String initPwd = employeeVo.getEmployeePhone().substring(5);
        String encryptPwd = RSAUtils.encrypt(initPwd);
        update(Wrappers.lambdaUpdate(ApiEmployee.class)
                .eq(ApiEmployee::getId, id)
                .set(ApiEmployee::getLoginPwd, encryptPwd)
        );
        StpKit.MANAGE.logout(id);
        return true;
    }

    @Override
    public Boolean updatePassword(ApiEmployeeBo employeeBo) {
        ApiEmployeeVo employee = getData(employeeBo.getId());
        String dbOldPwd = RSAUtils.decrypt(employee.getLoginPwd());
        String oldPwd = RSAUtils.decrypt(employeeBo.getLoginPwd());
        if (!StrUtil.equals(dbOldPwd, oldPwd)) {
            throw new JbkException("原密码输入错误，请重新输入");
        }
        String newPwd = RSAUtils.encrypt(RSAUtils.decrypt(employeeBo.getNewLoginPwd()));
        update(Wrappers.lambdaUpdate(ApiEmployee.class)
                .eq(ApiEmployee::getId, employeeBo.getId())
                .set(ApiEmployee::getLoginPwd, newPwd)
        );
        try {
            // 保存退出登录日志
            ApiLogLogin logLogin = LogLoginUtils.createLogLogin(
                    ApiEnum.LoginType.LOG_OUT,
                    employeeBo.getId(),
                    StpKit.DRIVER_MANAGE,
                    (String) StpKit.MANAGE.getExtra(StpKit.EXTRA_NAME)
            );
            logLoginService.saveData(logLogin);
        } catch (Exception e) {
        }
        StpKit.MANAGE.logout();
        return Boolean.TRUE;
    }

    @Override
    public Boolean delPositionById(Long id) {
        return update(Wrappers.lambdaUpdate(ApiEmployee.class)
                .eq(ApiEmployee::getId, id)
                .set(ApiEmployee::getPositionId, null)
        );
    }
}


