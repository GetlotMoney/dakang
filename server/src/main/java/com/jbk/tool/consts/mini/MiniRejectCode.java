package com.jbk.tool.consts.mini;

/**
 * 小程序扫码取水链路的拒绝码常量。miniapp（src/api/device.ts）的 real 适配器按下表把 int code
 * 映射为 ContractError 字符串码：
 *
 * <pre>
 *   5401  → INVALID_QR_CODE        码不存在/非法
 *   5402  → QR_EXPIRED             码已禁用/过期、或未绑定出水口视同不可用
 *   5403  → UNIVERSAL_CODE_PENDING 万能码，现场选设备/出水口流程待定型
 *   5410  → SCAN_SESSION_EXPIRED   扫码会话缺失/过期（前端提示“请重新扫码”）
 *   5411  → SCAN_QUOTE_CHANGED     扫码冻结报价与当前档案不一致（水种或单价已变）
 * </pre>
 *
 * DeviceAvailability 与 CardBlockCode 是 eligibility 接口的正常返回字段，不走本拒绝码；本类仅用于 resolve/context 阶段的异常式拒绝。
 *
 * @author dakang
 * @since 2026-07-19
 */
public final class MiniRejectCode {

    private MiniRejectCode() {
    }

    /** 二维码不存在或非法 → miniapp INVALID_QR_CODE */
    public static final int INVALID_QR_CODE = 5401;

    /** 二维码已禁用/过期 → miniapp QR_EXPIRED */
    public static final int QR_EXPIRED = 5402;

    /** 万能码待定型 → miniapp UNIVERSAL_CODE_PENDING */
    public static final int UNIVERSAL_CODE_PENDING = 5403;

    /** 扫码会话缺失或过期 → miniapp SCAN_SESSION_EXPIRED（文案“请重新扫码”） */
    public static final int SCAN_SESSION_EXPIRED = 5410;

    /** 扫码报价已变化 → miniapp SCAN_QUOTE_CHANGED。与 5410 分开：过期重扫即可，报价变化要提示价格已调整。 */
    public static final int SCAN_QUOTE_CHANGED = 5411;
}
