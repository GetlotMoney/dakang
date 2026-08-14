package com.jbk.serve.service.api.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiLogOperationBo;
import com.jbk.tool.data.api.po.ApiLogOperation;
import com.jbk.serve.mapper.api.ApiLogOperationMapper;
import com.jbk.serve.service.api.IApiLogOperationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.tool.utils.OptionalUtils;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
@Service
public class ApiLogOperationServiceImpl extends ServiceImpl<ApiLogOperationMapper, ApiLogOperation> implements IApiLogOperationService {

    @Override
    public void saveLogOperate(ApiLogOperation apiLogOperation) {
        save(apiLogOperation);
    }

    @Override
    public PageDataVo<ApiLogOperation> pageData(ApiLogOperationBo logOperationBo) {
        Page<ApiLogOperation> logOperationPage = page(new Page<>(logOperationBo.getCurrent(), logOperationBo.getSize()),
                Wrappers.lambdaQuery(ApiLogOperation.class)
                        .like(StrUtil.isNotEmpty(logOperationBo.getLogUserName()),
                                ApiLogOperation::getLogUserName, logOperationBo.getLogUserName()
                        )
                        // 模块与动作各自过滤各自的列：混成 OR 时「操作模块」筛选框会命中操作内容，
                        // 且两个维度无法叠加使用（查不到「某模块下的某个动作」）
                        .like(StrUtil.isNotEmpty(logOperationBo.getLogModule()),
                                ApiLogOperation::getLogModule, logOperationBo.getLogModule()
                        )
                        .like(StrUtil.isNotEmpty(logOperationBo.getLogContent()),
                                ApiLogOperation::getLogContent, logOperationBo.getLogContent()
                        )
                        .ge(StrUtil.isNotEmpty(logOperationBo.getLogExecuteTimeBegin()),
                                ApiLogOperation::getLogExecuteTime, logOperationBo.getLogExecuteTimeBegin()
                        )
                        .le(StrUtil.isNotEmpty(logOperationBo.getLogExecuteTimeEnd()),
                                ApiLogOperation::getLogExecuteTime, logOperationBo.getLogExecuteTimeEnd()
                        )
                        .eq(ObjectUtil.isNotNull(logOperationBo.getLogSuccessFlag()),
                                ApiLogOperation::getLogSuccessFlag, logOperationBo.getLogSuccessFlag()
                        )
                        .eq(StrUtil.isNotEmpty(logOperationBo.getLogIp()),
                                ApiLogOperation::getLogIp, logOperationBo.getLogIp()
                        )
                        .orderByDesc(ApiLogOperation::getId)
        );
        return PageDataVo.getPageData(logOperationPage);
    }

    @Override
    public ApiLogOperation getData(Long id) {
        ApiLogOperation operation = getById(id);
        OptionalUtils.nullToElseThrow(operation, "记录不存在");
        return operation;
    }
}


