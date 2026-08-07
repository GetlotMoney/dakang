package com.jbk.serve.controller.api;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.service.api.IApiDictTypeService;
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

    // 不记操作日志：字典读取是每个页面加载都会触发的基础读，曾占操作日志 97%（652/668 条），
    // 把触发对账/赠卡/裁决这些真正需要审计的记录整页淹没。操作日志只记写操作与敏感动作。
    @PostMapping("/getByType")
    @Operation(summary = "get字典列表", description = "传字典类型")
    public R<ApiDictType> getByType(
            @NotEmpty(message = "字典类型不为空")
            @RequestParam String dictType) {
        return R.ok(dictTypeService.getByType(dictType));
    }

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


