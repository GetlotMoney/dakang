/// <reference types="vite/client" />

declare const __DAKANG_BUILD_FINGERPRINT__: string

declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>
  export default component
}

interface ImportMetaEnv {
  readonly VITE_APP_TITLE: string
  readonly VITE_APP_PORT: string
  readonly VITE_UNI_APPID: string
  readonly VITE_WX_APPID: string
  readonly VITE_API_MODE: 'mock' | 'real'
  /** 按域覆盖 API 模式（未配置则回退 VITE_API_MODE）。 */
  readonly VITE_API_MODE_DEVICE?: 'mock' | 'real'
  readonly VITE_API_MODE_ORDER?: 'mock' | 'real'
  readonly VITE_API_MODE_CARD?: 'mock' | 'real'
  readonly VITE_API_MODE_RECHARGE?: 'mock' | 'real'
  /** L2-AUTH 正式微信登录入口；默认 mock（走 C01 原型入口），仅正式鉴权验收构建可覆盖为 real。 */
  readonly VITE_API_MODE_AUTH?: 'mock' | 'real'
  /** E2E-03 水配送链（包B）；默认 mock，显式 real 才接真 /mini/delivery/**。 */
  readonly VITE_API_MODE_DELIVERY?: 'mock' | 'real'
  /**
   * 测试登录手机号（仅隔离测试环境）。配置后入口页以该手机号向后端换取**真实 KH_USER 会话**，
   * 供 appid/appsecret 尚未配置时在真机上验收接真业务链。交付构建绝不配置此项。
   */
  readonly VITE_TEST_LOGIN_PHONE?: string
  readonly VITE_TEST_LOGIN_PHONES?: string
  readonly VITE_SERVER_BASEURL: string
  readonly VITE_DELETE_CONSOLE: 'true' | 'false'
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
