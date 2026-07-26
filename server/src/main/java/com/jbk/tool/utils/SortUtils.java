package com.jbk.tool.utils;

import cn.hutool.core.util.ObjectUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 *@ClassName SortUtils
 *@Author xs
 *@Date 2025/9/17 13:56
 *@Version 1.0
 */
public class SortUtils {

    public static <T> List<T> sortById(List<Long> idList, List<T> dataInfoList, Function<T, Long> idExtractor) {
        if (ObjectUtil.isEmpty(idList) || ObjectUtil.isEmpty(dataInfoList)) {
            return new ArrayList<>();
        }
        // 创建ID到对象的映射
        Map<Long, T> idToObjectMap = dataInfoList.stream()
                .collect(Collectors.toMap(idExtractor, item -> item, (existing, replacement) -> existing));

        // 按照idList的顺序构建结果列表
        List<T> result = new ArrayList<>();
        for (Long id : idList) {
            T item = idToObjectMap.get(id);
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }
}


