package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 机主授权范围投影（E2E-06，对齐 miniapp account.ts OwnerScope）。
 * 只用于登录上下文展示（profile「机主授权」等）；数据范围的强制过滤仍在各接口
 * 服务端逐次执行，本投影不构成授权（铁律6）。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniOwnerScopeVo", description = "机主授权范围投影")
public class MiniOwnerScopeVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "名下水站 ID（string，防精度丢失）")
    private List<String> stationIds;

    @Schema(description = "名下设备编号")
    private List<String> deviceNos;
}
