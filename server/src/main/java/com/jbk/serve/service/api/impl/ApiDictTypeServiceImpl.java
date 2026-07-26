package com.jbk.serve.service.api.impl;

import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.github.yulichang.base.MPJBaseServiceImpl;
import com.github.yulichang.toolkit.MPJWrappers;
import com.google.common.collect.Lists;
import com.jbk.tool.config.system.MyApplicationContext;
import com.jbk.tool.config.system.redis.consts.RedisExpire;
import com.jbk.tool.config.system.redis.consts.RedisKeys;
import com.jbk.tool.data.api.po.ApiDictData;
import com.jbk.tool.data.api.po.ApiDictType;
import com.jbk.serve.mapper.api.ApiDictTypeMapper;
import com.jbk.serve.service.api.IApiDictTypeService;
import com.jbk.tool.utils.OptionalUtils;
import com.jbk.tool.validator.ValidArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 字典类型表 服务实现类
 * </p>
 *
 * @author xs
 * @since 2025-09-04
 */
@Service
public class ApiDictTypeServiceImpl extends MPJBaseServiceImpl<ApiDictTypeMapper, ApiDictType>
        implements IApiDictTypeService {

    @Autowired
    private MyApplicationContext myApplicationContext;

    @Override
    @Cached(name = RedisKeys.Comm.JET_DICT_TYPE, key = "#dictType", expire = RedisExpire.MONTH_ONE_EXPIRE_, cacheType = CacheType.REMOTE)
    public ApiDictType getByType(String dictType) {
        ApiDictType apiDictType = selectJoinOne(ApiDictType.class,
                MPJWrappers.lambdaJoin(ApiDictType.class)
                        .leftJoin(ApiDictData.class, ApiDictData::getDictType, ApiDictType::getDictType)
                        .eq(ApiDictType::getDictType, dictType)
                        // 按字典排序值升序返回，否则 join 默认按 ID 降序，前端状态下拉全为倒序
                        .orderByAsc(ApiDictData::getDictSort)
                        .selectAsClass(ApiDictType.class, ApiDictType.class)
                        .selectCollection(ApiDictData.class, ApiDictType::getDictDataList));
        OptionalUtils.nullToElseThrow(apiDictType, "字典不存在");
        return apiDictType;
    }

    @Override
    public List<ApiDictType> listByType(ValidArrayList<String> dictTypeList) {
        IApiDictTypeService proxy = myApplicationContext.getProxy(IApiDictTypeService.class);
        List<ApiDictType> list = Lists.newArrayList();
        dictTypeList.forEach(e -> {
            list.add(proxy.getByType(e));
        });
        return list;
    }
}
