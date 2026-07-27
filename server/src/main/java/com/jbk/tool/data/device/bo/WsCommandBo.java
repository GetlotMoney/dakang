package com.jbk.tool.data.device.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 设备指令业务对象（后台中控下发 + 指令记录查询）
 * <p>安全边界：后台中控仅允许下发 3查询/4锁机/5解锁/6参数同步/7重启；
 * 出水类指令（1/2）必须由订单链路触发，禁止后台人工下发，防止绕开资金链造数。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsCommandBo", description = "设备指令业务对象")
public class WsCommandBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "指令信息不为空")
    private Long id;

    @Schema(description = "目标设备ID")
    @NotNull(groups = InsertGroup.class, message = "目标设备不为空")
    private Long deviceId;

    @Schema(description = "指令类型(1320)：仅允许 3查询状态 4锁机 5解锁 6参数同步 7重启")
    @NotNull(groups = InsertGroup.class, message = "指令类型不为空")
    private Integer cmdType;

    @Schema(description = "下发报文JSON（参数同步等需要，其余可空）")
    @Size(max = 2000, message = "下发报文长度不能超过2000")
    private String cmdPayload;

    // ===== 以下为查询筛选字段 =====

    @Schema(description = "【筛选】指令状态(1321)")
    private Integer cmdStatus;

    @Schema(description = "【筛选】指令号")
    private String cmdNo;
}
