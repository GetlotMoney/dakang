package com.jbk.serve.controller.api;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.service.api.IApiDictTypeService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.data.api.po.ApiDictType;
import com.jbk.tool.domain.R;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.validator.ValidArrayList;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * <p>
 * 字典类型表 前端控制器
 * </p>
 *
 * @author xs
 * @since 2025-09-04
 */
@Tag(name = "API-字典")
@Validated
@RestController
@RequestMapping("/api/dict")
public class ApiDictTypeController {

    @Autowired
    private IApiDictTypeService dictTypeService;

    @LogOperation
    @PostMapping("/getByType")
    @Operation(summary = "get字典列表", description = "传字典类型")
    public R<ApiDictType> getByType(
            @NotEmpty(message = "字典类型不为空")
            @RequestParam String dictType) {
        return R.ok(dictTypeService.getByType(dictType));
    }

    @LogOperation
    @PostMapping("/listByType")
    @Operation(summary = "list字典列表", description = "传字典类型")
    public R<List<ApiDictType>> listByType(
            @RequestBody ValidArrayList<String> dictTypeList) {
        if (ObjectUtil.isEmpty(dictTypeList)) {
            throw new JbkException("字典类型为空");
        }
        return R.ok(dictTypeService.listByType(dictTypeList));
    }
}


