package com.jbk.tool.data.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 高风险控制预览结果（E2E-05 包B，任务书 3.5 两步确认第一步）。
 *
 * <p>ticket 一次性、短时效；confirm 必须回显 targetDigest/paramDigest——
 * 前端若在预览后改了参数或目标，摘要对不上即拒绝，绝无「预览一套、执行另一套」。</p>
 *
 * @author dakang
 * @since 2026-07-30
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "DeviceControlPreviewVo", description = "高风险控制预览结果")
public class DeviceControlPreviewVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "操作凭据（一次性，confirm 领取即销毁）")
    private String operationTicket;

    @Schema(description = "凭据有效期（秒）")
    private Integer ticketTtlSeconds;

    @Schema(description = "指令类型(1320)")
    private Integer cmdType;

    @Schema(description = "指令类型描述")
    private String cmdTypeDesc;

    @Schema(description = "范围类型(1366)")
    private Integer scopeType;

    @Schema(description = "目标设备数量（服务端解析结果，非前端提交值）")
    private Integer targetCount;

    @Schema(description = "目标设备编号预览（最多前20台）")
    private List<String> deviceNos;

    @Schema(description = "目标摘要（confirm 回显用）")
    private String targetDigest;

    @Schema(description = "参数摘要（confirm 回显用）")
    private String paramDigest;

    @Schema(description = "本次确认是否需要二级认证（D-423 档位判据）：UI 据此提前告知运营，不让口令框成为随机出现的意外")
    private Boolean requireSafe;

    @Schema(description = "紧急停止锚定的活动订单号（仅紧急停止返回）")
    private String activeOrderNo;
}
