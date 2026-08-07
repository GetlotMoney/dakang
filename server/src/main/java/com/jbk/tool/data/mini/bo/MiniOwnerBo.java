package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 机主域入参（E2E-05 包E）。设备归属与数据范围全部由服务端按会话 userId 判定，
 * 本 Bo 刻意没有 userId 字段（铁律6）；按端点使用其中一部分字段，必填校验在服务端做。
 *
 * @author dakang
 * @since 2026-07-30
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniOwnerBo", description = "机主域入参")
public class MiniOwnerBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "设备编号（详情/申报）")
    @Size(max = 50, message = "设备编号过长")
    private String deviceNo;

    @Schema(description = "申报幂等键（前端生成，重试间保持不变；同键重复提交返回原申请）")
    @Size(max = 64, message = "申报请求标识过长")
    private String requestId;

    @Schema(description = "服务类型：REPAIR维修 / PART配件")
    @Size(max = 10, message = "服务类型不合法")
    private String serviceType;

    @Schema(description = "问题描述")
    @Size(max = 1000, message = "问题描述不能超过1000字")
    private String description;

    @Schema(description = "证据引用（受控 mediaKey，经 /mini/delivery/media/upload purpose=4 登记）")
    @Size(max = 9, message = "证据最多9张")
    private List<String> evidenceRefs;

    @Schema(description = "联系电话（仅格式校验；回显一律用账号注册手机号脱敏，不落库）")
    @Size(max = 20, message = "联系电话不合法")
    private String contactPhone;
}
