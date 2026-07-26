package com.jbk.serve.service.api;

import com.jbk.tool.data.api.bo.ApiRbacMenuBo;
import com.jbk.tool.data.api.po.ApiRbacMenu;
import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.api.vo.ApiRbacMenuTreeVo;
import com.jbk.tool.data.api.vo.ApiRbacMenuVo;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
public interface IApiRbacMenuService extends IService<ApiRbacMenu> {
    Long saveData(ApiRbacMenuBo rbacMenuBo);

    ApiRbacMenuVo getData(Long id);

    List<ApiRbacMenuTreeVo> treeData(List<Integer> menuTypeList);

    Boolean deleteData(Long id);

    Boolean updateData(ApiRbacMenuBo rbacMenuBo);
}


