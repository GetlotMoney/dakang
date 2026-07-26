package com.jbk.serve.service.api;

import com.jbk.tool.data.api.bo.ApiTagBo;
import com.jbk.tool.data.api.po.ApiTag;
import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.api.vo.ApiTagVo;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author xs
 * @since 2025-09-15
 */
public interface IApiTagService extends IService<ApiTag> {

    ApiTagVo getData(Long id);

    Boolean updateData(ApiTagBo tagBo);

    Long saveData(ApiTagBo tagBo);

    Boolean deleteData(Long id);

    List<ApiTagVo> listData(String tagName);
}


