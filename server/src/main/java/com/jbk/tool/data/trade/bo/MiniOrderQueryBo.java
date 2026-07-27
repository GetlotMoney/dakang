package com.jbk.tool.data.trade.bo;

import com.jbk.tool.data.PageBo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 小程序 C 端订单分页查询入参。
 * <p>不含 userId：查询范围一律按 KH_USER 会话强制过滤本人订单（铁律6），前端传 userId 无效。</p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniOrderQueryBo", description = "小程序订单分页查询入参")
public class MiniOrderQueryBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单类型(1340)：1扫码取水 2购卡充值 3水配送（空=全部）")
    private Integer orderType;

    @Schema(description = "订单状态(1341)（空=全部）")
    private Integer orderStatus;
}
