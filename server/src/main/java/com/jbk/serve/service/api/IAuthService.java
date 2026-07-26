package com.jbk.serve.service.api;

import com.jbk.tool.data.api.bo.AuthBo;
import com.jbk.tool.data.api.vo.ApiEmployeeLoginVo;

/**
 *@ClassName IAuthService
 *@Author xs
 *@Date 2025/9/8 9:43
 *@Version 1.0
 */
public interface IAuthService {
    ApiEmployeeLoginVo loginEmployee(AuthBo authBo);

    Boolean openSafe(String loginPwd);

    Boolean loginOut();


}


