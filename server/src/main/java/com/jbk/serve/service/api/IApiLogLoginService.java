package com.jbk.serve.service.api;

import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiLogLoginBo;
import com.jbk.tool.data.api.po.ApiLogLogin;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author xs
 * @since 2025-09-08
 */
public interface IApiLogLoginService extends IService<ApiLogLogin> {

    PageDataVo<ApiLogLogin> pageData(ApiLogLoginBo logLoginBo);

    ApiLogLogin getData(Long id);

    Boolean saveData(ApiLogLogin logLogin);
}
