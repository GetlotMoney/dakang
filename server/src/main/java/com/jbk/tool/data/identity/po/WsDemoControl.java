package com.jbk.tool.data.identity.po;

import com.baomidou.mybatisplus.annotation.*;
import com.jbk.tool.data.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("ws_demo_control")
public class WsDemoControl extends BaseEntity {
    @TableId(value = "ID", type = IdType.AUTO) private Long id;
    @TableField("USER_ID") private Long userId;
    @TableField("NEXT_PAY_RESULT") private String nextPayResult;
    @TableField("NEXT_DEVICE_RESULT") private String nextDeviceResult;
    @TableField("DELIVERY_AUTO") private Integer deliveryAuto;
}
