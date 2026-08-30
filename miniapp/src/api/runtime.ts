/**
 * 业务域标识。Mock 分流基建已整体退役，此类型仅作为 runtime-notice 按页面区域取提示文案的键。
 */
export type ApiDomain = 'device' | 'order' | 'card' | 'recharge' | 'auth' | 'delivery' | 'message'

/**
 * 微信「手机号快速验证」组件是否可用。主体未认证时平台在组件层禁用 open-type="getPhoneNumber"，
 * 点击无弹窗且回调不触发，只能在构建物里决定是否渲染按钮。当前主体已认证故为 true，留作停用开关。
 * 用代码常量而非环境变量：常量无注入环节，产物值恒等于源码值。
 */
export const WX_PHONE_COMPONENT_AVAILABLE = true

export function isPhoneComponentAvailable(): boolean {
  return WX_PHONE_COMPONENT_AVAILABLE
}

/**
 * 微信客服会话（`open-type="contact"`）是否可用。与手机号组件同类：未在微信后台开通时按钮点击无反应
 * 也无回调，只能在构建物里决定是否渲染。当前客服尚未开通故为 false，开通后改 true 放开入口。
 */
export const WX_CONTACT_AVAILABLE = false

export function isContactAvailable(): boolean {
  return WX_CONTACT_AVAILABLE
}

/**
 * 老板测试模式是构建期常量：只允许替换尚未接入的外部能力，不能切换业务 API 或绕过登录鉴权。
 * 正式构建缺省为 false，打包后无法由用户在页面或 storage 中开启。
 */
export function isDemoMode(): boolean {
  return import.meta.env.VITE_DEMO_MODE === 'true'
}

/** 测试包固定扫码内容；缺配返回 null，由扫码层明确拒绝而不是偷偷回落任意设备。 */
export function demoScanCode(): string | null {
  if (!isDemoMode()) {
    return null
  }
  const code = String(import.meta.env.VITE_DEMO_SCAN_CODE ?? '').trim()
  return code || null
}
