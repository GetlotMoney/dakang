package com.jbk.tool.data.settlement.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 区域服务商归属（版本化）：回答「这个区县/市/省的运营中心是谁」。
 * 按生效时间版本化：分润按订单创建时点归属计算，换人=插新版本行，直接改行会让历史订单改口径。
 * 本表不含比例；比例在 {@link WsSplitPlanItem}，待甲方书面确认。
 *
 * @author dakang
 * @since 2026-08-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_region_agent")
@Schema(name = "WsRegionAgent", description = "区域服务商归属（版本化）")
public class WsRegionAgent extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "区域层级：PROVINCE / CITY / COUNTY，与 SplitV2Enum.RegionLevel 同源")
    @TableField("REGION_LEVEL")
    private String regionLevel;

    @Schema(description = "行政区划码(GB/T 2260)")
    @TableField("REGION_CODE")
    private String regionCode;

    @Schema(description = "区域名称，仅供排障与后台展示，不参与匹配")
    @TableField("REGION_NAME")
    private String regionName;

    @Schema(description = "服务商用户ID(ws_user.ID)")
    @TableField("AGENT_USER_ID")
    private Long agentUserId;

    @Schema(description = "状态：1生效 2停用；停用行=自该时点起该区域无服务商")
    @TableField("AGENT_STATUS")
    private Integer agentStatus;

    @Schema(description = "生效时间（含）yyyyMMddHHmmss")
    @TableField("EFFECT_TIME")
    private String effectTime;

    @Schema(description = "备注：换签原因、合同号等")
    @TableField("AGENT_REMARK")
    private String agentRemark;
}
