package com.jbk.serve.service.settlement;

/**
 * 「0 元注册送」自动发放（D-418，2026-08-07 甲方确认）。
 *
 * @author dakang
 * @since 2026-08-07
 */
public interface IRegisterGiftService {

    /**
     * 注册建号成功后触发：开关开启且参数合法时发放注册赠卡；
     * 幂等（userId 确定性派生请求号），失败不抛出、不阻断注册。
     */
    void grantIfEnabled(Long userId);
}
