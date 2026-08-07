package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 机主交易快照查询入参（E2E-06，对齐 miniapp OwnerTransactionQuery）。
 * 无 userId 字段（铁律6）；分页与时间窗校验在服务端做（缺省近7日/第1页20条，size 上限 100）。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniOwnerTransactionBo", description = "机主交易快照查询入参")
public class MiniOwnerTransactionBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "周期起（yyyyMMddHHmmss，闭区间；缺省近7自然日起点）")
    @Size(max = 14, message = "时间格式不合法")
    private String periodStart;

    @Schema(description = "周期止（yyyyMMddHHmmss，闭区间；缺省当前时间）")
    @Size(max = 14, message = "时间格式不合法")
    private String periodEnd;

    @Schema(description = "按设备编号筛选（必须在本人名下，越权拒绝）")
    @Size(max = 50, message = "设备编号过长")
    private String deviceNo;

    @Schema(description = "页码（≥1，缺省 1）")
    private Long current;

    @Schema(description = "页大小（1~100，缺省 20）")
    private Long size;
}
