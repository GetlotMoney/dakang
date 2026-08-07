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
import java.util.List;

/**
 * 批量指令/高风险控制业务对象（E2E-05 包B）。
 *
 * <p>preview 用 {@code InsertGroup}（范围与命令参数）；confirm 只认 ticket + 摘要回显，
 * 命令参数一律以 preview 冻结在 Redis 里的为准——confirm 不接收也不相信新的参数，
 * 「参数变化拒绝」由摘要比对落实。confirm 三字段的非空校验在服务端做（fail-closed）。</p>
 *
 * @author dakang
 * @since 2026-07-30
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsCommandBatchBo", description = "批量指令业务对象")
public class WsCommandBatchBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "批次信息不为空")
    private Long id;

    // ===== preview（InsertGroup）=====

    @Schema(description = "范围类型(1366)：1指定设备 2指定水站 3全部设备")
    @NotNull(groups = InsertGroup.class, message = "范围类型不为空")
    private Integer scopeType;

    @Schema(description = "指定设备ID集合（scopeType=1 必填；服务端逐台重新核验档案）")
    @Size(max = 200, message = "单批指定设备不能超过200台")
    private List<Long> deviceIds;

    @Schema(description = "指定水站ID（scopeType=2 必填；目标集合由服务端按水站解析）")
    private Long stationId;

    @Schema(description = "指令类型(1320)：批量允许 3查询 4锁机 5解锁 6参数同步 7重启 8价格同步；2停止出水仅紧急停止单设备")
    @NotNull(groups = InsertGroup.class, message = "指令类型不为空")
    private Integer cmdType;

    @Schema(description = "参数本体JSON（参数同步/价格同步必填合法JSON，其余可空）")
    @Size(max = 2000, message = "参数报文长度不能超过2000")
    private String cmdPayload;

    // ===== confirm（非空校验在服务端，缺失一律拒绝）=====

    @Schema(description = "操作凭据（preview 返回，一次性，Redis GETDEL 原子领取）")
    private String operationTicket;

    @Schema(description = "目标摘要回显（preview 返回；与凭据内摘要不一致即拒绝）")
    private String targetDigest;

    @Schema(description = "参数摘要回显（preview 返回；与凭据内摘要不一致即拒绝）")
    private String paramDigest;

    // ===== 查询筛选 =====

    @Schema(description = "【筛选】批次号")
    private String batchNo;

    @Schema(description = "【筛选】聚合状态(1367)")
    private Integer batchStatus;

    @Schema(description = "【筛选】指令类型(1320)")
    private Integer filterCmdType;
}
