package com.jbk.serve.service.api.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiLogLoginBo;
import com.jbk.tool.data.api.po.ApiLogLogin;
import com.jbk.serve.mapper.api.ApiLogLoginMapper;
import com.jbk.serve.service.api.IApiLogLoginService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.tool.utils.OptionalUtils;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author xs
 * @since 2025-09-08
 */
@Service
public class ApiLogLoginServiceImpl extends ServiceImpl<ApiLogLoginMapper, ApiLogLogin> implements IApiLogLoginService {

    @Override
    public PageDataVo<ApiLogLogin> pageData(ApiLogLoginBo logLoginBo) {
        Page<ApiLogLogin> loginPage = page(new Page<>(logLoginBo.getCurrent(), logLoginBo.getSize()), Wrappers.lambdaQuery(ApiLogLogin.class)
                .like(StrUtil.isNotEmpty(logLoginBo.getLogUserName()),
                        ApiLogLogin::getLogUserName, logLoginBo.getLogUserName()
                )
                .ge(StrUtil.isNotEmpty(logLoginBo.getLogExecuteTimeBegin()),
                        ApiLogLogin::getLogExecuteTime, logLoginBo.getLogExecuteTimeBegin()
                )
                .le(StrUtil.isNotEmpty(logLoginBo.getLogExecuteTimeEnd()),
                        ApiLogLogin::getLogExecuteTime, logLoginBo.getLogExecuteTimeEnd()
                )
                .eq(ObjectUtil.isNotNull(logLoginBo.getLogType()),
                        ApiLogLogin::getLogType, logLoginBo.getLogType()
                )
                .eq(StrUtil.isNotEmpty(logLoginBo.getLogIp()),
                        ApiLogLogin::getLogIp, logLoginBo.getLogIp()
                )
                .orderByDesc(ApiLogLogin::getId)
        );
        return PageDataVo.getPageData(loginPage);
    }

    @Override
    public ApiLogLogin getData(Long id) {
        ApiLogLogin apiLogLogin = getById(id);
        OptionalUtils.nullToElseThrow(apiLogLogin, "登录日志不存在");
        return apiLogLogin;
    }

    @Override
    public Boolean saveData(ApiLogLogin logLogin) {
        save(logLogin);
        return Boolean.TRUE;
    }
}
