package com.jbk.tool.data.identity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class MiniDemoControlVo {
    private boolean demoMode;
    private String fixedQrContent;
    private String nextPayResult;
    private String nextDeviceResult;
    private Integer deliveryAuto;
    private String channelInviteCode;
    private String regionProvinceInviteCode;
    private String regionCityInviteCode;
    private String regionCountyInviteCode;
}
