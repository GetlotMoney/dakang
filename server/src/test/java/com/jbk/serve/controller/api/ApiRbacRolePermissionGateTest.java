package com.jbk.serve.controller.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.api.bo.ApiRbacRoleBo;
import com.jbk.tool.data.api.bo.ApiRbacRoleEmployeeBo;
import com.jbk.tool.data.api.bo.ApiRbacRoleMenuBo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色域写接口的权限码闭锁。
 *
 * <p>为什么要有这个测试：RBAC 的两个入口——重写角色菜单绑定（updateMenu）与把人塞进角色
 * （addEmployeeToRoleList）——曾经都只校验 api:role:update（菜单 631「修改角色」）。
 * 运行时权限 = 角色已授菜单行 MENU_API_PERMS 的并集（ApiRbacRoleServiceImpl#getUserPermission，
 * 无超管旁路），而 Service 层既不限定目标角色是否为调用者自身，也没有「只能授出自己已有权限」的
 * 天花板。于是「能改角色名称」= 给自己所在角色勾满全部菜单 = 一次请求自授 finance:config:edit、
 * mall:aftersale:refund、device:command:send 等全部功能点，等价超管。
 *
 * <p>前端 system/role/index.vue 与 system/user/index.vue 的按钮一直按 api:role:permission /
 * api:employee:assignRole 显示，两个功能点在 01-base.sql（646 / 645）里也早就建好——错配只在后端。
 * 这类错配没有任何编译期信号，改注解一行就能悄悄退回去，故用反射把权限码钉死在测试里。
 *
 * <p>反向验红：把 updateMenu 的权限码改回 "api:role:update"，本测试立即失败。
 */
class ApiRbacRolePermissionGateTest {

    /** 重写角色菜单绑定必须由「分配权限」把关，不能与「修改角色基本信息」共用一个码。 */
    @Test
    void updateMenuIsGatedByRolePermissionPoint() throws NoSuchMethodException {
        Method method = ApiRbacRoleController.class.getDeclaredMethod("updateMenu", ApiRbacRoleMenuBo.class);
        assertEquals("api:role:permission", singlePermission(method));
        assertNotEquals("api:role:update", singlePermission(method),
                "updateMenu 用 api:role:update 把关 = 能改角色名即可自授全部功能点");
    }

    /** 给人加角色必须由「分配角色」把关：把自己加进超管角色与改角色名不是一个风险级别。 */
    @Test
    void addEmployeeToRoleListIsGatedByAssignRolePoint() throws NoSuchMethodException {
        Method method = ApiRbacRoleController.class
                .getDeclaredMethod("addEmployeeToRoleList", ApiRbacRoleEmployeeBo.class);
        assertEquals("api:employee:assignRole", singlePermission(method));
        assertNotEquals("api:role:update", singlePermission(method),
                "addEmployeeToRoleList 用 api:role:update 把关 = 能改角色名即可把自己加进超管角色");
    }

    /** 反向边界：真正的「改角色基本信息」仍然是 api:role:update，不能被上面两条顺手改宽或改窄。 */
    @Test
    void updateDataStillUsesRoleUpdatePoint() throws NoSuchMethodException {
        Method method = ApiRbacRoleController.class.getDeclaredMethod("updateData", ApiRbacRoleBo.class);
        assertEquals("api:role:update", singlePermission(method));
    }

    /**
     * 取该方法声明的唯一权限码。
     *
     * <p>顺带守住「只剩登录检查」这种退化：SaCheckOrAspect 对 login/permission 是顺序 AND，
     * 删掉 permission 数组不会有任何编译或运行期报错，接口会静默降级成「登录即可调用」。
     */
    private String singlePermission(Method method) {
        MySaCheckOr checkOr = method.getAnnotation(MySaCheckOr.class);
        assertNotNull(checkOr, method.getName() + " 缺少 @MySaCheckOr");
        assertTrue(checkOr.login().length > 0, method.getName() + " 丢了登录校验");
        assertEquals(1, checkOr.permission().length, method.getName() + " 必须声明且只声明一个权限码");
        SaCheckPermission permission = checkOr.permission()[0];
        assertEquals(1, permission.value().length, method.getName() + " 权限码必须唯一");
        return permission.value()[0];
    }
}
