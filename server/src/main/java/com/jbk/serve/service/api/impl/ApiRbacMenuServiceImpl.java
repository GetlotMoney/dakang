package com.jbk.serve.service.api.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.common.collect.Lists;
import com.jbk.serve.service.api.IApiRbacRoleEmployeeService;
import com.jbk.serve.service.api.IApiRbacRoleService;
import com.jbk.tool.data.api.bo.ApiRbacMenuBo;
import com.jbk.tool.data.api.po.ApiRbacMenu;
import com.jbk.serve.mapper.api.ApiRbacMenuMapper;
import com.jbk.serve.service.api.IApiRbacMenuService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.tool.data.api.po.ApiRbacRoleMenu;
import com.jbk.serve.service.api.IApiRbacRoleMenuService;
import org.springframework.beans.factory.annotation.Autowired;
import com.jbk.tool.data.api.vo.ApiRbacMenuTreeVo;
import com.jbk.tool.data.api.vo.ApiRbacMenuVo;
import com.jbk.tool.utils.OptionalUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
@Service
public class ApiRbacMenuServiceImpl extends ServiceImpl<ApiRbacMenuMapper, ApiRbacMenu> implements IApiRbacMenuService {

    @Autowired
    private IApiRbacRoleMenuService roleMenuService;


    @Autowired
    @Lazy
    private IApiRbacRoleService apiRbacRoleService;

    @Override
    public Long saveData(ApiRbacMenuBo rbacMenuBo) {
        // 校验信息
        long cntName = count(Wrappers.lambdaQuery(ApiRbacMenu.class)
                .eq(ApiRbacMenu::getMenuName, rbacMenuBo.getMenuName())
                .eq(ApiRbacMenu::getMenuParentId, rbacMenuBo.getMenuParentId())
        );
        OptionalUtils.gtZeroElseThrow(cntName, "名称信息已存在");
        // 保存信息
        ApiRbacMenu menu = BeanUtil.copyProperties(rbacMenuBo, ApiRbacMenu.class);
        save(menu);
        return menu.getId();
    }

    @Override
    public Boolean updateData(ApiRbacMenuBo rbacMenuBo) {
        // 校验信息
        long cntName = count(Wrappers.lambdaQuery(ApiRbacMenu.class)
                .ne(ApiRbacMenu::getId, rbacMenuBo.getId())
                .eq(ApiRbacMenu::getMenuName, rbacMenuBo.getMenuName())
                .eq(ApiRbacMenu::getMenuParentId, rbacMenuBo.getMenuParentId())
        );
        OptionalUtils.gtZeroElseThrow(cntName, "名称信息已存在");
        ApiRbacMenu menu = BeanUtil.copyProperties(rbacMenuBo, ApiRbacMenu.class);
        updateById(menu);
        // 删除该功能对应角色的所有人员的权限
        apiRbacRoleService.delByMenuIdCache(rbacMenuBo.getId());
        return Boolean.TRUE;

    }

    @Override
    public ApiRbacMenuVo getData(Long id) {
        ApiRbacMenu rbacMenu = getById(id);
        OptionalUtils.nullToElseThrow(rbacMenu, "菜单不存在");
        ApiRbacMenuVo rbacMenuVo = BeanUtil.copyProperties(rbacMenu, ApiRbacMenuVo.class);
        return rbacMenuVo;
    }

    @Override
    public List<ApiRbacMenuTreeVo> treeData(List<Integer> menuTypeList) {
        List<ApiRbacMenu> apiRbacMenuList = list(Wrappers.lambdaQuery(ApiRbacMenu.class)
                .in(ObjectUtil.isNotEmpty(menuTypeList), ApiRbacMenu::getMenuType, menuTypeList)
                .orderByAsc(ApiRbacMenu::getMenuParentId)
                .orderByDesc(ApiRbacMenu::getMenuSort)
        );
        if (ObjectUtil.isEmpty(apiRbacMenuList)) {
            return Lists.newArrayList();
        }
        //根据ParentId进行分组
        Map<Long, List<ApiRbacMenu>> parentMap = apiRbacMenuList.stream()
                .collect(Collectors.groupingBy(ApiRbacMenu::getMenuParentId, Collectors.toList()));
        List<ApiRbacMenuTreeVo> menuTreeVoList = this.buildMenuTree(parentMap, NumberUtils.LONG_ZERO);
        return menuTreeVoList;
    }

    @Override
    public Boolean deleteData(Long id) {
        //是否有子级菜单
        long childCount = count(Wrappers.lambdaQuery(ApiRbacMenu.class)
                .eq(ApiRbacMenu::getMenuParentId, id));
        OptionalUtils.gtZeroElseThrow(childCount, "存在子级菜单，无法删除");
        //是否被角色引用
        long roleMenuCount = roleMenuService.count(Wrappers.lambdaQuery(ApiRbacRoleMenu.class)
                .eq(ApiRbacRoleMenu::getMenuId, id));
        OptionalUtils.gtZeroElseThrow(roleMenuCount, "菜单已被角色使用，无法删除");
        return removeById(id);
    }


    // 构建菜单树
    private List<ApiRbacMenuTreeVo> buildMenuTree(Map<Long, List<ApiRbacMenu>> parentMap, Long parentId) {
        // 获取本级菜单树List
        List<ApiRbacMenuTreeVo> res = parentMap.getOrDefault(parentId, Lists.newArrayList()).stream()
                .map(e -> BeanUtil.copyProperties(e, ApiRbacMenuTreeVo.class)).collect(Collectors.toList());
        // 循环遍历下级菜单
        res.forEach(e -> {
            e.setChildren(this.buildMenuTree(parentMap, e.getId()));
        });
        return res;
    }
}


