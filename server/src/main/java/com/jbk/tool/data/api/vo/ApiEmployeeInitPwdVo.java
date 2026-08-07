package com.jbk.tool.data.api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 建号/重置密码的一次性初始口令回执（R-201）。
 *
 * <p>明文初始口令只在本次响应中出现一次：库内只存 BCrypt 哈希，事后无法再取回。
 * 两条日志链路都必须省略响应体才成立——请求日志（RequestAspect）与操作日志表
 * （SysLogAspect → api_log_operation）共用 {@code responseSummaryForLog}；后者曾整体序列化
 * 响应，使本字段明文落库并可经日志查询接口读出，已于 R-201 整改。
 * 页面必须一次性展示并提示转交员工，员工首次登录被强制改密。</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "ApiEmployeeInitPwdVo", description = "一次性初始密码回执")
public class ApiEmployeeInitPwdVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "员工ID")
    private Long id;

    @Schema(description = "一次性初始密码：仅本次响应可见，需转交员工，首次登录强制修改")
    private String initialPwd;
}
