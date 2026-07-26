package com.jbk.tool.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.jbk.serve.service.api.IApiDictTypeService;
import com.jbk.tool.annotation.AutoDict;
import com.jbk.tool.annotation.Dict;
import com.jbk.tool.consts.ApiConst;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.po.ApiDictData;
import com.jbk.tool.domain.R;
import com.jbk.tool.exception.ErrorMsg;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.lang.reflect.Field;
import java.util.*;


@Component
@Aspect
@Slf4j
@Order(200)
public class DictAspect {
    @Resource
    private ObjectMapper objectMapper;

    @Autowired
    private IApiDictTypeService dictTypeService;

    @Pointcut("execution(public * com.jbk.serve.controller..*Controller.*(..)) && @annotation(com.jbk.tool.annotation.AutoDict)")
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
            result = this.parseDict(result);
        } catch (Exception e) {
            log.error("=========》Exception：{},原因：{}", ErrorMsg.DICT_PARSE_ERROR.getMsg(), ExceptionUtil.stacktraceToString(e));
        } finally {
            return result;
        }
    }

    private Object parseDict(Object result) throws Exception {
        if (result instanceof R) {
            if (((R<?>) result).getData() instanceof PageDataVo) {
                List<Object> records = ((PageDataVo) ((R<?>) result).getData()).getList();
                if (ObjectUtil.isEmpty(records) ||
                        (records.size() > 0 && records.get(0) instanceof String)) {
                    return result;
                }
                List<JSONObject> items = this.resultIsList(records);
                ((PageDataVo) ((R<?>) result).getData()).setList(items);
            } else if (((R<?>) result).getData() instanceof Collection) {
                Collection<Object> data = (Collection) ((R<?>) result).getData();
                List<Object> list = BeanUtil.copyToList(data, Object.class);
                if (ObjectUtil.isEmpty(list) ||
                        (list.size() > 0 && list.get(0) instanceof String)) {
                    return result;
                }
                List<JSONObject> items = this.resultIsList(data);
                ((R<Collection>) result).setData(items);
            } else {
                Object data = ((R) result).getData();
                if (ObjectUtil.isNull(data) ||
                        !isNonPrimitiveType(data.getClass())) {
                    return result;
                }
                JSONObject item = this.resultIsDetail(data);
                ((R) result).setData(item);
            }
        }
        return result;
    }

    private JSONObject resultIsDetail(Object record) throws Exception {
        if (ObjectUtil.isNull(record)) {
            return null;
        }
        Set<Field> dictFieldList = Sets.newHashSet();
        Set<String> typeSet = Sets.newHashSet();
        String json = objectMapper.writeValueAsString(record);
        JSONObject item = JSONObject.parseObject(json, Feature.OrderedField);
        parseDict(record, item, dictFieldList, typeSet);
        Map<String, List<ApiDictData>> allDict = this.getAllDict(typeSet);
        fillDict(dictFieldList, item, allDict);
        return item;

    }


    private List<JSONObject> resultIsList(Collection<Object> records) throws Exception {
        if (ObjectUtil.isEmpty(records)) {
            return Lists.newArrayList();
        }
        List<JSONObject> items = Lists.newArrayList();
        Set<Field> dictFieldList = Sets.newHashSet();
        Set<String> typeSet = Sets.newHashSet();
        for (Object record : records) {
            String json = objectMapper.writeValueAsString(record);
            JSONObject item = JSONObject.parseObject(json, Feature.OrderedField);
            if (ObjectUtil.isNull(record)) {
                items.add(item);
                continue;
            }
            parseDict(record, item, dictFieldList, typeSet);
            items.add(item);
        }
        Map<String, List<ApiDictData>> allDict = this.getAllDict(typeSet);
        for (JSONObject item : items) {
            fillDict(dictFieldList, item, allDict);
        }
        return items;
    }


    private void parseDict(Object record, JSONObject item, Set<Field> dictFieldList, Set<String> set) throws Exception {
        Class<?> clazz = record.getClass();
        List<Field> fieldList = Lists.newArrayList();
        while (ObjectUtil.isNotNull(clazz)) {
            fieldList.addAll(Lists.newArrayList(Arrays.asList(clazz.getDeclaredFields())));
            clazz = clazz.getSuperclass();
        }
        Field[] fields = new Field[fieldList.size()];
        fieldList.toArray(fields);
        for (Field field : fields) {
            String value = item.getString(field.getName());
//            注释：值为空的时候一样进行解析，解析为null
//            if (StrUtil.isBlank(value)) continue;
            // AutoDict 注解
            if (ObjectUtil.isNotNull(field.getAnnotation(AutoDict.class))) {
                field.setAccessible(true);
                Object obj = field.get(record);
                if (obj instanceof Collection) {
                    List<JSONObject> resultItems = this.resultIsList((Collection<Object>) obj);
                    item.put(field.getName(), resultItems);
                } else {
                    JSONObject resultItem = this.resultIsDetail(obj);
                    item.put(field.getName(), resultItem);
                }
            }
            // Dict 注解
            if (ObjectUtil.isNull(field.getAnnotation(Dict.class)) || dictFieldList.contains(field)) {
                continue;
            }
            dictFieldList.add(field);
            String type = field.getAnnotation(Dict.class).dictType().value();
            set.add(type);
        }
    }



    private void fillDict(Set<Field> dictFieldList, JSONObject item, Map<String, List<ApiDictData>> allDict) {
        dictFieldList.stream().forEach(field -> {
            String type = field.getAnnotation(Dict.class).dictType().value();
            String valueStr = item.getString(field.getName());
            if (StrUtil.isEmpty(valueStr)) {
                item.put(field.getName() + ApiConst.DICT_TEXT_SUFFIX, valueStr);
                return;
            }
            List<ApiDictData> dictDatas = allDict.get(type);
            if (ObjectUtil.isNull(dictDatas) || dictDatas.size() == ApiConst.Number.ZERO) {
                return;
            }
            List<String> valueLists = StrUtil.split(valueStr, ",");
            String dictText = "";
            String dictTextClass = "";
            for (String value : valueLists) {
                for (ApiDictData dictData : dictDatas) {
                    if (Integer.valueOf(value).compareTo(dictData.getDictValue()) == 0) {
                        dictText += dictData.getDictLabel() + ",";
                        if (StrUtil.isEmpty(dictData.getDictClass())) {
                            dictTextClass += ",";
                        } else {
                            dictTextClass += dictData.getDictClass() + ",";
                        }
                    }
                }
            }
            if (StrUtil.isEmpty(dictText)) {
                dictText = valueStr;
            } else {
                dictText = dictText.substring(0, dictText.length() - 1);
                dictTextClass = dictTextClass.substring(0, dictTextClass.length() - 1);
            }
            // 将翻译数据添加到对象中
            item.put(field.getName() + ApiConst.DICT_TEXT_SUFFIX, dictText);
            item.put(field.getName() + ApiConst.DICT_TEXT_CLASS_SUFFIX, dictTextClass);
        });
    }


    private Map<String, List<ApiDictData>> getAllDict(Set<String> typeSet) {
        Map<String, List<ApiDictData>> allDict = Maps.newHashMap();
        for (String dictType : typeSet) {
            List<ApiDictData> list = dictTypeService.getByType(dictType).getDictDataList();
            allDict.put(dictType, list);
        }
        return allDict;
    }

    public static boolean isNonPrimitiveType(Class<?> clazz) {
        return !clazz.isPrimitive() &&
                !Number.class.isAssignableFrom(clazz) &&
                !String.class.isAssignableFrom(clazz) &&
                !Boolean.class.isAssignableFrom(clazz);
    }
}


