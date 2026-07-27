package com.jbk.tool.data.mini.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 小程序配送准入状态 Vo（E2E-03 包B；对齐 miniapp delivery.ts CourierAdmission）。
 * <p>status 在后端 ws_courier 1350（1待审核 2启用 3停用 4驳回）之上补 0=未提交
 * （无准入记录）；准入申请提交是 PC 人工建档（B09），小程序侧本 Vo 只读。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "MiniCourierAdmissionVo", description = "小程序配送准入状态")
public class MiniCourierAdmissionVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "账号ID（=userId，统一账号模型）")
    private Long accountId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "准入状态：0未提交 1待审核 2已启用 3已停用 4审核驳回")
    private Integer status;

    @Schema(description = "申请人姓名")
    private String applicantName;

    @Schema(description = "联系电话（PhoneMask 脱敏）")
    private String maskedPhone;

    @Schema(description = "服务水站ID集（已启用即授权范围；接单校验仍在 Service 强制）")
    private List<Long> requestedStationIds;

    @Schema(description = "服务区域")
    private String requestedRegion;

    @Schema(description = "记录提交/建档时间")
    private String submittedTime;

    @Schema(description = "驳回原因（仅状态4）")
    private String rejectReason;
}
