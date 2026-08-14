package com.jbk.tool.data.mall.bo;

import cn.hutool.json.JSONUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 模拟物流事件入参（E2E-09 L1，仅隔离环境）。
 *
 * <p>刻意没有包裹ID与订单号：事实只带运单号，归属由服务端反查——
 * 收下调用方给的包裹ID等于允许把任意事件挂到任意包裹上。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
@Data
@Accessors(chain = true)
public class MallLogisticsSimEventBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "承运商编码")
    @NotBlank(message = "承运商编码不能为空")
    @Size(max = 32, message = "承运商编码过长")
    private String providerCode;

    @Schema(description = "承运方事件唯一键：同键即同一条事实，重放复用原行")
    @NotBlank(message = "事件唯一键不能为空")
    @Size(max = 128, message = "事件唯一键过长")
    private String providerEventKey;

    @Schema(description = "运单号")
    @NotBlank(message = "运单号不能为空")
    @Size(max = 64, message = "运单号过长")
    private String waybillNo;

    @Schema(description = "事件状态(1407)：CREATED/PICKED_UP/IN_TRANSIT/DELIVERED/SIGNED/EXCEPTION/CANCELLED")
    @NotBlank(message = "事件状态不能为空")
    @Size(max = 32, message = "事件状态过长")
    private String eventState;

    @Schema(description = "事件时间：14位业务时间")
    @NotBlank(message = "事件时间不能为空")
    @Size(max = 14, message = "事件时间格式非法")
    private String eventTime;

    @Schema(description = "事件描述：承运方文案")
    @Size(max = 200, message = "事件描述过长")
    private String eventDesc;

    @Schema(description = "签名：sha256(providerCode:eventKey:waybillNo:eventState:eventTime:secret)")
    @NotBlank(message = "签名不能为空")
    @Size(max = 64, message = "签名格式非法")
    private String signature;

    /** 留证用的原始报文：签名不入正文摘要，避免同一事实因签名重算而被判为改参。 */
    public String rawBody() {
        return JSONUtil.createObj()
                .set("providerCode", providerCode)
                .set("providerEventKey", providerEventKey)
                .set("waybillNo", waybillNo)
                .set("eventState", eventState)
                .set("eventTime", eventTime)
                .set("eventDesc", eventDesc)
                .toString();
    }
}
