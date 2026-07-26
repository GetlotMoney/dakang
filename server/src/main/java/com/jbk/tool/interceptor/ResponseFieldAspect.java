package com.jbk.tool.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.jbk.tool.annotation.ResponseFieldApiExclude;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.exception.ErrorMsg;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @ClassName ResponseFieldAspect
 * @Author xs
 * @Date 2024/6/27 10:31
 * @Version 1.0
 */
@Component
@Aspect
@Slf4j
@Order(100)
public class ResponseFieldAspect {
    @Resource
    private ObjectMapper objectMapper;

    @Pointcut("execution(public * com.jbk.serve.controller..*Controller.*(..)) && @annotation(com.jbk.tool.annotation.ResponseFieldApiExclude)")
    public void pointcut() {
    }

    @Around("pointcut()")
    public Object doAround(ProceedingJoinPoint pjp) throws Throwable {
        Object result = null;
        try {
            result = pjp.proceed();
            if (ObjectUtil.isNull(result)) {
                return result;
            }
        } catch (Throwable e) {
            throw e;
        }
        try {
            MethodSignature methodSignature = (MethodSignature) pjp.getSignature();
            ResponseFieldApiExclude exclude = methodSignature.getMethod().getAnnotation(ResponseFieldApiExclude.class);
            if (exclude == null) {
                return result;
            }
            List<String> excludeFieldList = Lists.newArrayList(exclude.value());
            result = this.parseResponseField(result, excludeFieldList);
        } catch (Exception e) {
            log.error("=========》Exception：{},原因：{}", ErrorMsg.ERROR_CUSTOM.getMsg(), ExceptionUtil.stacktraceToString(e));
        } finally {
            return result;
        }
    }

    private Object parseResponseField(Object result, List<String> excludeFieldList) throws Exception {
        if (result instanceof R) {
            if (((R<?>) result).getData() instanceof PageDataVo) {
                List<Object> records = ((PageDataVo) ((R<?>) result).getData()).getList();
                if (ObjectUtil.isNull(records) ||
                        (records.size() > 0 && records.get(0) instanceof String)) {
                    return result;
                }
                List<JSONObject> items = this.resultIsList(records, excludeFieldList);
                ((PageDataVo) ((R<?>) result).getData()).setList(items);
            } else if (((R<?>) result).getData() instanceof Collection) {
                Collection<Object> data = (Collection) ((R<?>) result).getData();
                List<Object> list = BeanUtil.copyToList(data, Object.class);
                if (ObjectUtil.isNull(list) ||
                        (list.size() > 0 && list.get(0) instanceof String)) {
                    return result;
                }
                List<JSONObject> items = this.resultIsList(data, excludeFieldList);
                ((R<Collection>) result).setData(items);
            } else {
                Object data = ((R) result).getData();
                if (ObjectUtil.isNull(data)
                        || data instanceof String) {
                    return result;
                }
                JSONObject item = this.resultIsDetail(data, excludeFieldList);
                ((R) result).setData(item);
            }
        }
        return result;
    }

    private JSONObject resultIsDetail(Object record, List<String> excludeFieldList) throws Exception {
        List<String> sonFile = excludeFieldList.stream().filter(e -> e.contains("."))
                .map(e -> e.substring(0, e.indexOf(".")))
                .collect(Collectors.toList());
        if (ObjectUtil.isNull(record)) {
            return null;
        }
        String json = objectMapper.writeValueAsString(record);
        JSONObject item = JSONObject.parseObject(json, Feature.OrderedField);
        Set<String> fileNameSet = item.keySet();
        JSONObject newItem = new JSONObject();
        for (String fieldName : fileNameSet) {
            if (excludeFieldList.contains(fieldName)) {
                continue;
            }
            Object fileObj = item.get(fieldName);
            if (null == fileObj) {
                newItem.put(fieldName, fileObj);
            } else if (sonFile.contains(fieldName) && fileObj instanceof Iterable) {
                List<String> sonExcludeFieldList = excludeFieldList.stream()
                        .filter(e -> e.contains(fieldName + "."))
                        .map(e -> e.substring(e.indexOf(".") + 1))
                        .collect(Collectors.toList());
                if (sonExcludeFieldList.size() != 0) {
                    List<JSONObject> resultItems = this.resultIsList((Collection<Object>) fileObj, sonExcludeFieldList);
                    newItem.put(fieldName, resultItems);
                }
            } else if (sonFile.contains(fieldName) && isNonPrimitiveType(fileObj.getClass())) {
                List<String> sonExcludeFieldList = excludeFieldList.stream()
                        .filter(e -> e.contains(fieldName + "."))
                        .map(e -> e.substring(e.indexOf(".") + 1))
                        .collect(Collectors.toList());
                if (sonExcludeFieldList.size() != 0) {
                    JSONObject resultItem = this.resultIsDetail(fileObj, sonExcludeFieldList);
                    newItem.put(fieldName, resultItem);
                }
            } else {
                newItem.put(fieldName, fileObj);
            }
        }
        return newItem;

    }


    private List<JSONObject> resultIsList(Collection<Object> records, List<String> excludeFieldList) throws Exception {
        if (ObjectUtil.isEmpty(records)) {
            return null;
        }
        List<JSONObject> items = Lists.newArrayList();
        for (Object record : records) {
            JSONObject item = resultIsDetail(record, excludeFieldList);
            items.add(item);
        }
        return items;
    }

    public static boolean isNonPrimitiveType(Class<?> clazz) {
        return !clazz.isPrimitive() && !Number.class.isAssignableFrom(clazz) && !clazz.equals(String.class);
    }
}


