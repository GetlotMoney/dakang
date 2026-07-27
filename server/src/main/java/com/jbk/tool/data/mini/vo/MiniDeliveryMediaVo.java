package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 小程序配送媒体登记结果 Vo（E2E-03 A5：受控媒体键；重复登记幂等返回同键）。
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Accessors(chain = true)
@Schema(name = "MiniDeliveryMediaVo", description = "配送媒体登记结果")
public class MiniDeliveryMediaVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "受控媒体键（签收三照/举证只提交本键，禁止直传文件路径）")
    private String mediaKey;
}
