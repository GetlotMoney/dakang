package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 水卡带期权益批次摘要 Vo（D-415：「合并后会显示多久到期」的展示载体）。
 *
 * <p>只下发剩余与到期，不下发批次内部编号/来源/退款态——那些是后台对账口径，
 * 用户视角只需要「哪部分权益、什么时候到期」。</p>
 */
@Data
@Schema(name = "MiniCardBundleVo", description = "水卡带期权益批次摘要")
public class MiniCardBundleVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "剩余余额(分)")
    private Long remainFen;

    @Schema(description = "剩余水量(毫升)")
    private Long remainMl;

    @Schema(description = "到期时间(yyyyMMddHHmmss)")
    private String expireTime;
}
