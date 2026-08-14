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
 * 机主区域归属链（D-406/D-407/D-428）：这个机主的省/市/区县运营中心各是谁。
 * 一人一行、建立即冻结；跨行政区放置设备不改归属（血缘不地缘）。无行=公域未分配，
 * 推荐与区域份额全部落平台余数。公域流量由后台人工分配（PUBLIC_MANUAL）补行。
 * 三级可任意留空（缺席层级差切片按 D-428 归最近在场上级，全空才归公司），
 * 但三级在场者必须互不相同——同人兼两级会让分账行聚合产生取整漂移，服务层拒绝。
 *
 * @author dakang
 * @since 2026-08-14
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_owner_attribution")
@Schema(name = "WsOwnerAttribution", description = "机主区域归属链（一人一行，建立即冻结）")
public class WsOwnerAttribution extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "机主用户ID(ws_user.ID)")
    @TableField("OWNER_USER_ID")
    private Long ownerUserId;

    @Schema(description = "归属来源：PRIVATE_REFERRAL 血缘 / PUBLIC_MANUAL 公域人工分配")
    @TableField("ATTRIBUTION_SOURCE")
    private String attributionSource;

    @Schema(description = "省级运营中心用户ID；可空=该级无人")
    @TableField("PROVINCE_AGENT_USER_ID")
    private Long provinceAgentUserId;

    @Schema(description = "市级运营中心用户ID；可空=该级无人")
    @TableField("CITY_AGENT_USER_ID")
    private Long cityAgentUserId;

    @Schema(description = "区县级运营中心用户ID；可空=该级无人")
    @TableField("COUNTY_AGENT_USER_ID")
    private Long countyAgentUserId;

    @Schema(description = "建立时点 yyyyMMddHHmmss；建立即冻结")
    @TableField("BIND_TIME")
    private String bindTime;

    @Schema(description = "备注：分配依据、合同号等")
    @TableField("ATTRIBUTION_REMARK")
    private String attributionRemark;
}
