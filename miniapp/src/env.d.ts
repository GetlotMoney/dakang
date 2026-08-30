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
  /** 后端基址（含 /dakangApi 前缀）。真机联调改 env/.env.development.local，构建钩子会校正 IP。 */
  readonly VITE_SERVER_BASEURL: string
  readonly VITE_DELETE_CONSOLE: 'true' | 'false'
  /** 老板测试包：只替换支付、扫码与外部设备能力，页面和后端业务事务仍走正式实现。 */
  readonly VITE_DEMO_MODE?: 'true' | 'false'
  /** 测试包点击扫码时提交给真实扫码解析接口的演示二维码内容。 */
  readonly VITE_DEMO_SCAN_CODE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
