package com.jbk.serve.service.api;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiLogOperationBo;
import com.jbk.tool.data.api.po.ApiLogOperation;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
public interface IApiLogOperationService extends IService<ApiLogOperation> {

    void saveLogOperate(ApiLogOperation apiLogOperation);

    PageDataVo<ApiLogOperation> pageData(ApiLogOperationBo logOperationBo);

    ApiLogOperation getData(Long id);
}


