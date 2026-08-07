package com.jbk.serve.controller.compliance;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.compliance.IWsAuditExportTaskService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.annotation.SwaggerApiInclude;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.compliance.bo.WsAuditExportTaskBo;
import com.jbk.tool.data.compliance.vo.WsAuditExportTaskVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import com.jbk.tool.validator.group.PageGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计导出任务（B23 / REQ-024、REQ-066）。
 *
 * <p>只提供申请、查询与失败重试；不提供下载端点——文件生成依赖对象存储尚未接入，
 * 提供一个必然返回空的下载入口比没有入口更容易被误读成"导出已可用"。</p>
 *
 * <p>路径挂在 {@code /api} 前缀下（同 logOperation/domainEvent 等系统管理域接口）：
 * {@code /system} 不在 Vite 代理与 nginx 反代的前缀清单里，新开前缀要双端同步，
 * 系统管理域没有理由单独开一个。</p>
 *
 * @author dakang
 * @since 2026-08-06
 */
@Tag(name = "合规-审计导出")
@Validated
@RestController
@RequestMapping("/api/auditExport")
public class WsAuditExportTaskController {

    @Autowired
    private IWsAuditExportTaskService auditExportTaskService;

    @Operation(summary = "分页查询")
    @PostMapping("/pageData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "system:audit:export", type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<WsAuditExportTaskVo>> pageData(
            @SwaggerApiInclude({"current", "size", "taskStatus"})
            @RequestBody
            @Validated(PageGroup.class)
            WsAuditExportTaskBo bo) {
        return R.ok(auditExportTaskService.pageList(bo));
    }

    @LogOperation
    @RepeatSubmit
    @Operation(summary = "提交导出申请")
    @PostMapping("/apply")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "system:audit:export", type = StpKit.DRIVER_MANAGE)})
    public R<WsAuditExportTaskVo> apply(
            @SwaggerApiInclude({"exportScope", "startTime", "endTime", "operatorKeyword",
                    "businessKeyword", "applyReason"})
            @RequestBody
            @Validated(InsertGroup.class)
            WsAuditExportTaskBo bo) {
        return R.ok(auditExportTaskService.apply(bo));
    }

    @LogOperation
    @RepeatSubmit
    @Operation(summary = "重试失败任务")
    @PostMapping("/retry")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "system:audit:export", type = StpKit.DRIVER_MANAGE)})
    public R<WsAuditExportTaskVo> retry(
            @SwaggerApiInclude({"id"})
            @RequestBody
            @Validated(IdGroup.class)
            WsAuditExportTaskBo bo) {
        return R.ok(auditExportTaskService.retry(bo.getId()));
    }
}
