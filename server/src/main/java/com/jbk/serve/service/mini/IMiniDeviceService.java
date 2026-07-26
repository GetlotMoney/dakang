package com.jbk.serve.service.mini;

import com.jbk.tool.data.mini.vo.ScanSessionInfo;
import com.jbk.tool.data.mini.vo.ScanSessionVo;
import com.jbk.tool.data.mini.vo.WaterDeviceContextVo;
import com.jbk.tool.data.mini.vo.WaterEligibilityVo;

/**
 * 小程序设备/扫码取水服务（L1a 扫码链）。
 * <p>
 * 登录人一律由 {@code StpKit.KH_USER} 会话取，接口不接受前端 userId（安全铁律6）。
 * </p>
 *
 * @author dakang
 * @since 2026-07-19
 */
public interface IMiniDeviceService {

    /**
     * 扫码解析：码原文 → 设备/出水口，铸造一次性扫码会话（Redis TTL 300s）。
     * 拒绝时抛 JbkException（code 见 {@link com.jbk.tool.consts.mini.MiniRejectCode}）。
     *
     * @param rawCode 扫码原文（ws_qrcode.QRCODE_CONTENT）
     * @param userId  登录用户ID（会话取）
     */
    ScanSessionVo resolveScan(String rawCode, Long userId);

    /**
     * 取水设备上下文：按会话读设备/水站/出水口，校验会话归属登录人。
     */
    WaterDeviceContextVo getWaterContext(String scanSessionId, Long userId);

    /**
     * 取水资格校验：设备组（可用性）+ 卡组（阻断）双分组返回。
     * <p>CARD-SCOPE：只预检调用方指定的 {@code cardId}（后端不再自行挑卡，杜绝「预检卡A、下单卡B」）；
     * 他人卡与不存在卡统一 CARD_NOT_ACCESSIBLE，范围解析失败/未命中分别 CARD_SCOPE_INVALID/
     * CARD_SCOPE_DENIED。预检不是安全边界，下单事务内仍会二次校验。</p>
     *
     * @param cardId 待预检水卡ID；null 按未持卡返回 CARD_MISSING
     */
    WaterEligibilityVo checkEligibility(String scanSessionId, Long cardId, Long userId);

    /**
     * 供下单链复用：校验并返回扫码会话（设备/出水口）。会话缺失/过期/不属登录人抛 JbkException。
     * 只读取不消费；创单成功后由调用方 {@link #consumeScanSession(String)} 消费（M4）。
     */
    ScanSessionInfo loadScanSession(String scanSessionId, Long userId);

    /**
     * 消费（删除）扫码会话，仅在创单成功后调用，保证一次性（M4）。
     */
    void consumeScanSession(String scanSessionId);
}
