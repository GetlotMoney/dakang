package com.jbk.tool.data.mall.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 商城可分配配送员候选 Vo（E2E-09 S3）。
 *
 * <p>候选只是给人看的列表，不构成授权：分配动作服务端一律重新校验准入、停用、
 * 前置仓范围与自配送。</p>
 *
 * @author dakang
 * @since 2026-08-09
 */
@Data
@Accessors(chain = true)
public class MallCourierCandidateVo {

    @Schema(description = "配送员ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long courierId;

    @Schema(description = "配送员姓名")
    private String courierName;

    @Schema(description = "配送员电话（脱敏）")
    private String courierPhone;
}
