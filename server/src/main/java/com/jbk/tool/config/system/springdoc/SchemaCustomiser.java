package com.jbk.tool.config.system.springdoc;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.jbk.tool.annotation.SwaggerApiExclude;
import com.jbk.tool.annotation.SwaggerApiInclude;
import com.jbk.tool.exception.JbkException;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.*;

/**
 * @ClassName SchemaCustomiser1
 * @Author xs
 * @Date 2025/8/22 10:24
 * @Version 1.0
 */
@Component
public class SchemaCustomiser implements OpenApiCustomizer {

    private final ApplicationContext applicationContext;

    public SchemaCustomiser(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (ObjectUtil.isNull(openApi.getComponents()) || ObjectUtil.isNull(openApi.getComponents().getSchemas())) {
            return;
        }
        Map<String, Schema> schemaMap = openApi.getComponents().getSchemas();
        RequestMappingHandlerMapping mapping = applicationContext.getBean(RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = mapping.getHandlerMethods();

        // 构建路径到HandlerMethod的映射
        Map<String, HandlerMethod> handlerMap = buildHandlerMap(handlerMethods);

        // 处理每个路径
        openApi.getPaths().forEach((path, pathItem) -> {
            Arrays.asList(
                            pathItem.getPost(), pathItem.getPut(), pathItem.getPatch(), pathItem.getDelete()
                    ).stream()
                    .filter(Objects::nonNull)
                    .findFirst()
                    .ifPresent(operation -> {
                        HandlerMethod handlerMethod = handlerMap.get(path);
                        if (handlerMethod != null) {
                            processHandlerMethod(path,handlerMethod, operation, openApi, schemaMap);
                        }
                        String newOperationId = newPath(path);
                        if(StrUtil.isNotEmpty(newOperationId)){
                            operation.operationId(newOperationId);
                        }
                    });
        });
    }

    /**
     * 处理方法处理器
     */
    private void processHandlerMethod(String path,HandlerMethod handlerMethod, Operation operation,
                                      OpenAPI openApi, Map<String, Schema> schemaMap) {
        MethodParameter[] methodParameters = handlerMethod.getMethodParameters();
        if (ObjectUtil.isEmpty(methodParameters)) {
            return;
        }

        // 查找带有@RequestBody注解的参数
        Arrays.stream(methodParameters)
                .filter(param -> param.hasParameterAnnotation(RequestBody.class))
                .findFirst()
                .ifPresent(param -> {
                    SwaggerApiInclude apiInclude = param.getParameterAnnotation(SwaggerApiInclude.class);
                    SwaggerApiExclude apiExclude = param.getParameterAnnotation(SwaggerApiExclude.class);

                    if (apiInclude != null && apiExclude != null) {
                        throw new JbkException("SwaggerApiExclude / SwaggerApiInclude 只能存在一个");
                    }
                    if(apiInclude == null && apiExclude == null){
                        return;
                    }
                    List<String> includeList = apiInclude != null ?
                            Arrays.asList(apiInclude.value()) : Collections.emptyList();
                    List<String> excludeList = apiExclude != null ?
                            Arrays.asList(apiExclude.value()) : Collections.emptyList();

                    String className = extractClassName(param);
                    // 处理Schema
                    operation.getRequestBody().getContent().forEach((mediaType, content) -> {
                        Set<String> schemaSet = Sets.newHashSet();
                        Schema<?> currentSchema = content.getSchema();

                        if(currentSchema == null){
                            return;
                        }
                        String newName = className + "_" + newPath(path);
                        // 处理直接引用
                        if (currentSchema.get$ref() != null && currentSchema.get$ref().endsWith(className)) {
                            Schema<?> originSchema = schemaMap.get(className);
                            if(ObjectUtil.isEmpty(originSchema.getProperties())){
                                return;
                            }
                            Schema<?> customSchema = createCustomSchema(openApi, schemaSet, originSchema, includeList, excludeList);
                            openApi.getComponents().addSchemas(newName, customSchema);
                            content.setSchema(new Schema<>().$ref("#/components/schemas/" + newName));
                        }
                        // 处理数组引用
                        else if (currentSchema instanceof ArraySchema) {
                            Schema<?> itemsSchema = ((ArraySchema) currentSchema).getItems();
                            if(itemsSchema == null || itemsSchema.get$ref() == null || !itemsSchema.get$ref().endsWith(className)){
                                return;
                            }
                            Schema<?> originSchema = schemaMap.get(className);
                            if (ObjectUtil.isNotEmpty(originSchema.getProperties())) {
                                Schema<?> customSchema = createCustomSchema(openApi, schemaSet, originSchema, includeList, excludeList);
                                openApi.getComponents().addSchemas(newName, customSchema);

                                ArraySchema newArraySchema = new ArraySchema();
                                newArraySchema.setItems(new Schema<>().$ref("#/components/schemas/" + newName));
                                content.setSchema(newArraySchema);
                            }
                        }
                    });
                });
    }

    /**
     * 创建自定义Schema
     */
    private Schema<?> createCustomSchema(OpenAPI openApi, Set<String> schemaSet, Schema<?> original,
                                         List<String> includeList, List<String> excludeList) {
        // 循环引用处理
        if (schemaSet.contains(original.get$ref())) {
            Schema<?> tempSchema = new Schema<>();
            tempSchema.setType(original.getType());
            return tempSchema;
        }
        if (original.get$ref() != null) {
            schemaSet.add(original.get$ref());
        }
        Schema<?> custom = new Schema<>();
        custom.setType(original.getType());
        custom.setDescription(original.getDescription());

        Map<String, Schema> originalProperties = original.getProperties();
        if (ObjectUtil.isEmpty(originalProperties)) {
            return custom;
        }

        // 处理包含或排除逻辑
        if (ObjectUtil.isNotEmpty(includeList)) {
            Map<String, Schema> newProperties = processIncludeList(openApi, schemaSet, originalProperties, includeList);
            custom.setProperties(newProperties);
        } else if (ObjectUtil.isNotEmpty(excludeList)) {
            Map<String, Schema> newProperties = processExcludeList(openApi, schemaSet, originalProperties, excludeList);
            custom.setProperties(newProperties);
        } else {
            custom.setProperties(originalProperties);
        }

        return custom;
    }

    /**
     * 处理包含列表
     */
    private Map<String, Schema> processIncludeList(OpenAPI openApi, Set<String> schemaSet,
                                                   Map<String, Schema> originalProperties, List<String> includeList) {
        Map<String, Schema> newProperties = Maps.newHashMap();

        // 处理直接包含的字段
        includeList.stream()
                .filter(field -> !field.contains("."))
                .forEach(field -> {
                    if (originalProperties.containsKey(field)) {
                        newProperties.put(field, originalProperties.get(field));
                    }
                });

        // 处理嵌套字段
        Map<String, List<String>> nestedFields = parseNestedFields(includeList);
        nestedFields.forEach((parentField, nestedIncludeList) -> {
            if (originalProperties.containsKey(parentField)) {
                Schema<?> parentSchema = originalProperties.get(parentField);
                Schema<?> customParentSchema = processNestedField(openApi, schemaSet, parentSchema, nestedIncludeList, Collections.emptyList());
                newProperties.put(parentField, customParentSchema);
            }
        });

        return newProperties;
    }

    /**
     * 处理排除列表
     */
    private Map<String, Schema> processExcludeList(OpenAPI openApi, Set<String> schemaSet,
                                                   Map<String, Schema> originalProperties, List<String> excludeList) {
        Map<String, Schema> newProperties = Maps.newHashMap(originalProperties);

        // 处理直接排除的字段
        excludeList.stream()
                .filter(field -> !field.contains("."))
                .forEach(newProperties::remove);

        // 处理嵌套字段
        Map<String, List<String>> nestedFields = parseNestedFields(excludeList);
        nestedFields.forEach((parentField, nestedExcludeList) -> {
            if (newProperties.containsKey(parentField)) {
                Schema<?> parentSchema = newProperties.get(parentField);
                Schema<?> customParentSchema = processNestedField(openApi, schemaSet, parentSchema, Collections.emptyList(), nestedExcludeList);
                newProperties.put(parentField, customParentSchema);
            }
        });

        return newProperties;
    }

    /**
     * 处理嵌套字段
     */
    private Schema<?> processNestedField(OpenAPI openApi, Set<String> schemaSet, Schema<?> originalSchema,
                                         List<String> includeList, List<String> excludeList) {
        // 获取实际引用的Schema
        Schema<?> targetSchema = getReferencedSchema(openApi, originalSchema);
        if (targetSchema == null) {
            return originalSchema;
        }

        // 创建自定义的嵌套Schema
        String newName = targetSchema.getName() + "_Nested_" + IdUtil.fastSimpleUUID();
        Schema<?> customSchema = createCustomSchema(openApi, schemaSet, targetSchema, includeList, excludeList);
        openApi.getComponents().addSchemas(newName, customSchema);

        // 返回对自定义Schema的引用
        if (originalSchema instanceof ArraySchema) {
            ArraySchema newArraySchema = new ArraySchema();
            newArraySchema.setItems(new Schema<>().$ref("#/components/schemas/" + newName));
            return newArraySchema;
        } else {
            return new Schema<>().$ref("#/components/schemas/" + newName);
        }
    }

    /**
     * 获取引用的实际Schema
     */
    private Schema<?> getReferencedSchema(OpenAPI openApi, Schema<?> schema) {
        if (schema.get$ref() != null) {
            String ref = schema.get$ref();
            String schemaName = ref.substring(ref.lastIndexOf("/") + 1);
            return openApi.getComponents().getSchemas().get(schemaName);
        }

        if (schema instanceof ArraySchema) {
            Schema<?> items = ((ArraySchema) schema).getItems();
            return getReferencedSchema(openApi, items);
        }

        return schema;
    }

    /**
     * 提取类名
     */
    private String extractClassName(MethodParameter param) {
        String typeName = param.getParameter().getParameterizedType().getTypeName();
        List<String> classInfo = StrUtil.split(typeName, ".");
        return classInfo.get(classInfo.size() - 1);
    }

    /**
     * 解析嵌套字段
     */
    public static Map<String, List<String>> parseNestedFields(List<String> fields) {
        if (ObjectUtil.isEmpty(fields)) {
            return Maps.newHashMap();
        }

        Map<String, List<String>> result = new HashMap<>();
        for (String field : fields) {
            if (field.contains(".")) {
                String[] parts = field.split("\\.", 2);
                String parentField = parts[0];
                String nestedField = parts[1];
                result.computeIfAbsent(parentField, k -> new ArrayList<>()).add(nestedField);
            }
        }
        return result;
    }


    /**
     * 构建路径到HandlerMethod的映射
     */
    private Map<String, HandlerMethod> buildHandlerMap(Map<RequestMappingInfo, HandlerMethod> handlerMethods) {
        Map<String, HandlerMethod> handlerMap = Maps.newHashMap();
        handlerMethods.forEach((info, method) -> {
            // 获取所有模式（包括带路径变量的）
            Set<String> patterns = info.getPatternsCondition().getPatterns();
            if (ObjectUtil.isNotEmpty(patterns)) {
                patterns.forEach(pattern -> handlerMap.put(pattern, method));
            }
        });
        return handlerMap;
    }

    private static String newPath(String path){
        if(StrUtil.isEmpty(path)){
            return path;
        }
        String newPath = path.replace("/", "_");
        if(newPath.charAt(0) == '_'){
            return newPath.substring(1);
        }
        return newPath;
    }
}


