package com.jbk.tool.data.message.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 管理端消息记录查询入参（E2E-07 包D / REQ-087「后台记录」）。
 * 管理端有权按收件用户检索（运营排查），与小程序读端的铁律6会话过滤是两个门面。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsMessageRecordBo", description = "管理端消息记录查询入参")
public class WsMessageRecordBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "收件用户ID筛选")
    private Long userId;

    @Schema(description = "消息领域(1312)筛选")
    private Integer msgDomain;

    @Schema(description = "发送状态(1313)筛选")
    private Integer sendStatus;

    @Schema(description = "页码（≥1，缺省 1）")
    private Long current;

    @Schema(description = "页大小（1~100，缺省 20）")
    private Long size;
}
