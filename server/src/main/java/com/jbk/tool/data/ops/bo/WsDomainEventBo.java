package com.jbk.tool.data.ops.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 白名单领域事件查询入参（E2E-07 包B / REQ-083 只读订阅面）。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsDomainEventBo", description = "白名单领域事件查询入参")
public class WsDomainEventBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "事件类型(1363)筛选；必须在可订阅白名单内，缺省不筛")
    private Integer eventType;

    @Schema(description = "页码（≥1，缺省 1）")
    private Long current;

    @Schema(description = "页大小（1~100，缺省 20）")
    private Long size;
}
