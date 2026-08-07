package com.jbk.serve.service.mini;

import java.util.List;

/**
 * 小程序账号能力投影（E2E-03 包B：配送能力入口投影）。
 *
 * <p>能力投影只改善导航，不构成授权（miniapp/AGENTS.md）：配送接口的数据范围仍由
 * CourierAccess 在每次调用时按会话强制解析；本投影漂移最多导致入口显隐不准，
 * 不会放大任何 Service 数据范围。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
public interface IMiniCapabilityService {

    /**
     * 按用户投影能力清单：USER_BASE + COURIER_APPLY 为基础投影（所有正式用户可查看/申请配送准入）；
     * 最新配送员记录处于启用态时追加 COURIER_WORK；有归属行（设备或水站 OWNER_USER_ID）
     * 时追加 OWNER_VIEW/OWNER_SERVICE（E2E-05）。
     */
    List<String> capabilitiesOf(Long userId);

    /**
     * 机主授权范围投影（E2E-06）：名下水站 ID + 名下设备编号，登录上下文展示用。
     * 与 {@link #capabilitiesOf} 的 OWNER_VIEW 判定同源（同两张表的 OWNER_USER_ID）；
     * 无任何归属返回 null（上下文不携带该字段）。投影不构成授权（铁律6）。
     */
    com.jbk.tool.data.mini.vo.MiniOwnerScopeVo ownerScopeOf(Long userId);
}
