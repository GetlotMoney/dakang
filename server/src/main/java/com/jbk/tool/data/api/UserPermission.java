package com.jbk.tool.data.api;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 *@ClassName UserPermission
 *@Author xs
 *@Date 2025/9/5 11:18
 *@Version 1.0
 */
@Data
public class UserPermission implements Serializable {

    /**
     * 权限列表
     */
    private List<String> permissionList;

    /**
     * 角色列表
     */
    private List<String> roleList;


}


