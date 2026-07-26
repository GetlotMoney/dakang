package com.jbk.serve.service.api;

import com.github.yulichang.base.MPJBaseService;
import com.jbk.tool.data.api.bo.ApiDeptBo;
import com.jbk.tool.data.api.po.ApiDept;
import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.api.vo.ApiDeptTreeVo;
import com.jbk.tool.data.api.vo.ApiDeptVo;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
public interface IApiDeptService extends MPJBaseService<ApiDept> {

    ApiDeptVo getData(Long id);

    List<ApiDeptTreeVo> treeData();

    Long saveData(ApiDeptBo deptBo);

    Boolean deleteData(Long id);

    Boolean updateData(ApiDeptBo deptBo);
}


