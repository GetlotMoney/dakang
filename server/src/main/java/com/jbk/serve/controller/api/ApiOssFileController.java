package com.jbk.serve.controller.api;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.api.IApiOssFileService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.FileInfo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.NotNull;

/**
 * <p>
 * 文件 前端控制器
 * </p>
 *
 * @author xs
 * @since 2025-09-04
 */
@Tag(name = "API-文件")
@Validated
@RestController
@RequestMapping("/api/ossFile")
public class ApiOssFileController {

    @Autowired
    private IApiOssFileService ossFileService;

    @LogOperation
    @PostMapping("/saveFile")
    @Operation(summary = "单文件上传", description = "字段：file")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<FileInfo> saveFile(
            @NotNull(message = "file不为空") MultipartFile file
    ) {
        return R.ok(ossFileService.saveFile(file));
    }


}


