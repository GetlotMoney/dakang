package com.jbk.tool.data.user.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 用户配送地址簿（2026-08-02 家庭/地址链路落地）。
 * <p>CONTACT_PHONE 展示必须经 PhoneMask，原文不回流前端；配送下单按 addressId
 * 由服务端解引用取号写任务快照（铁律6）。IS_DEFAULT 同用户至多一条，由服务层事务互斥。</p>
 *
 * @author dakang
 * @since 2026-08-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_user_address")
public class WsUserAddress extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    /** 归属用户（ws_user.ID）；读写恒以会话人过滤 */
    private Long userId;

    /** 联系人姓名(max30) */
    private String contactName;

    /** 联系电话；展示必须经 PhoneMask 脱敏 */
    private String contactPhone;

    /** 省市区(max100) */
    private String region;

    /**
     * 收货区县行政区码(6位)：商城选仓的唯一判据（E2E-09 S2 增列）。
     * <p>可空：存量地址只有 REGION 自由文本，禁止按文本猜测回填——猜错会把订单
     * 派给根本不覆盖该地址的前置仓。未选区县的地址在商城结算处 fail-closed 提示补选。</p>
     */
    private String districtCode;

    /** 详细地址(max200) */
    private String addressDetail;

    /** 默认地址：1是 0否 */
    private Integer isDefault;

    /** 是否已授权定位取点：1是 0否 */
    private Integer locationAuthorized;
}
