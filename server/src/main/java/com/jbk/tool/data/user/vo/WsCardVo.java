package com.jbk.tool.data.user.vo;

import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 水卡响应对象
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsCardVo", description = "水卡响应对象")
public class WsCardVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "卡号(max32)")
    private String cardNo;

    @Schema(description = "卡类型(1331)：1虚拟卡 2实体卡")
    private Integer cardType;

    @Schema(description = "持卡用户ID")
    private Long userId;

    @Schema(description = "持卡人姓名（关联 ws_user 派生）")
    private String userName;

    @Schema(description = "持卡人手机号（关联 ws_user 派生）")
    private String userPhone;

    @Schema(description = "余额(分)")
    private Long balanceAmount;

    @Schema(description = "剩余水量(毫升)")
    private Long balanceMl;

    @Schema(description = "最近购买套餐ID")
    private Long packageId;

    @Schema(description = "套餐快照JSON")
    private String packageSnap;

    @Schema(description = "可用范围JSON（授权范围模型预留）")
    private String scopeJson;

    @Schema(description = "到期时间，空=永久")
    private String expireTime;

    @Schema(description = "卡状态(1332)：1正常 2冻结 3已过期 4已注销")
    private Integer cardStatus;

    @Schema(description = "生效授权成员数（关联 ws_card_member 派生）")
    private Long memberCount;

    @Schema(description = "授权成员列表（详情接口返回）")
    private List<WsCardMemberVo> memberList;

    @Schema(description = "备注(max500)")
    private String cardRemark;
}
