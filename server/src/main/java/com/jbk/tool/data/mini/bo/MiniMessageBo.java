package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 小程序消息中心入参（E2E-07 包B，对齐 miniapp message.ts 冻结契约）。
 * 无 userId 字段（铁律6：收件范围只认会话）；分页与领域合法性校验在服务端做。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniMessageBo", description = "小程序消息中心入参")
public class MiniMessageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "消息领域(1312)筛选：1取水 2卡券 3配送 4机主 5系统；缺省不筛")
    private Integer msgDomain;

    @Schema(description = "消息ID（detail/read 用）")
    private Long messageId;

    @Schema(description = "页码（≥1，缺省 1）")
    private Long current;

    @Schema(description = "页大小（1~100，缺省 20）")
    private Long size;
}
