import { ContractError } from './common'

/** 全局 API 模式（未按域覆盖时的回退值）。 */
export const apiMode = import.meta.env.VITE_API_MODE

/**
 * 支持按域覆盖的业务域标识。新增域接真时在此登记，并在 domainOverrideMode 增加对应静态读取。
 * 目前 device 域（扫码取水链，L1a-MP）、order 域（下单/订单/详情，L1e-MP）、card 域（水卡余额，L1f-MP）已接真；
 * recharge 域（L2 充值链）自 L2-T 起后端已实现（创单→支付事实→入账），代码层硬锁解除，按域覆盖判定；
 * auth 域（L2-AUTH 正式微信登录/绑手机）默认 mock（走 C01 原型入口），仅在显式 Real 入口构建下翻 real；
 * delivery 域（E2E-03 水配送链，包B）后端 /mini/delivery/** 已实现，按域覆盖判定——
 * 与 recharge 域同一先例：只有显式 real 才接真，漏配回落 mock。
 */
export type ApiDomain = 'device' | 'order' | 'card' | 'recharge' | 'auth' | 'delivery'

/** 模式解析的环境快照（纯函数入参，测试可直接构造任意组合，含全局 real）。 */
export interface ApiModeEnvironment {
  globalMode?: string
  deviceMode?: string
  orderMode?: string
  cardMode?: string
  rechargeMode?: string
  authMode?: string
  deliveryMode?: string
}

/**
 * 纯函数模式解析（2026-07-20 最终收口轮）：域覆盖优先，未配置回退全局；
 * 只有显式 'real' 才走真实适配器。抽为纯函数使「全局 real + recharge mock」等组合可被真实测试。
 */
export function resolveApiMode(domain: ApiDomain | undefined, env: ApiModeEnvironment): 'mock' | 'real' {
  // L2-T 起充值链后端已实现（创单/支付事实/入账全链），代码层硬锁解除，改回按域覆盖判定。
  // 仍然「只有显式 real 才接真」：漏配环境变量时回落 Mock，不会在没配好的环境里误动真钱。
  const override
    = domain === 'device'
      ? env.deviceMode
      : domain === 'order'
        ? env.orderMode
        : domain === 'card'
          ? env.cardMode
          : domain === 'recharge'
            ? env.rechargeMode
            : domain === 'auth'
              ? env.authMode
              : domain === 'delivery'
                ? env.deliveryMode
                : undefined
  const mode = override || env.globalMode
  return mode === 'real' ? 'real' : 'mock'
}

/**
 * 读取构建期环境（必须静态引用具体 `import.meta.env.VITE_API_MODE_<DOMAIN>`，
 * 不用动态键，保证 Vite / uni（含 mp-weixin）构建期正确内联替换）。
 */
function buildTimeEnvironment(): ApiModeEnvironment {
  return {
    globalMode: import.meta.env.VITE_API_MODE,
    deviceMode: import.meta.env.VITE_API_MODE_DEVICE,
    orderMode: import.meta.env.VITE_API_MODE_ORDER,
    cardMode: import.meta.env.VITE_API_MODE_CARD,
    rechargeMode: import.meta.env.VITE_API_MODE_RECHARGE,
    authMode: import.meta.env.VITE_API_MODE_AUTH,
    deliveryMode: import.meta.env.VITE_API_MODE_DELIVERY,
  }
}

/**
 * 选择 Mock / Real 适配器：签名向后兼容——旧调用不传 domain 仍走全局，
 * 避免"全局翻 real"导致其它尚未接真域的 realAdapterPending 集体抛错。
 */
export function selectAdapter<T>(mockAdapter: T, realAdapter: T, domain?: ApiDomain): T {
  return resolveApiMode(domain, buildTimeEnvironment()) === 'real' ? realAdapter : mockAdapter
}

/** 当前构建期某域的生效模式（供页面/契约按域分流，如 recharge mock 单走本地读取）。 */
export function currentMode(domain?: ApiDomain): 'mock' | 'real' {
  return resolveApiMode(domain, buildTimeEnvironment())
}

/**
 * 运行期 API 模式指纹（E2E 取证安全闸读取）——**全仓唯一发射点**。
 *
 * 段序与 `miniapp/e2e/runtime-modes.js` 的 `DOMAIN_ORDER` 严格一致；新增业务域必须同时改这两处。
 * 入口页与首页都必须调用本函数，禁止各自手工拼接：曾因首页另写一份 5 段常量（漏 AUTH），
 * 导致 E1b 安全闸整串比对恒不相等、三种模式全部卡在第 01 步。
 */
export function buildRuntimeModes(): string {
  return [
    `GLOBAL=${apiMode}`,
    `DEVICE=${currentMode('device')}`,
    `ORDER=${currentMode('order')}`,
    `CARD=${currentMode('card')}`,
    `RECHARGE=${currentMode('recharge')}`,
    `AUTH=${currentMode('auth')}`,
    `DELIVERY=${currentMode('delivery')}`,
  ].join(';')
}

/**
 * 纯函数：是否允许以 Mock 原型账号建立会话/展示原型账号面板——全部业务域（含 auth）均为 Mock 才允许。
 *
 * 复审 B：任一业务域接真（如 auth=mock 但 device/order/card=real）时，
 * 入口页不得用 Mock 账号恢复会话进首页，否则会话失效后会带着原型身份打真实接口。
 */
export function isMockAccountEntryAllowed(env: ApiModeEnvironment): boolean {
  return (['device', 'order', 'card', 'recharge', 'auth', 'delivery'] as const)
    .every(domain => resolveApiMode(domain, env) === 'mock')
}

/** 构建期版本：只有全部业务域（含 auth）都为 Mock 时，才允许原型账号选择与场景重置面板。 */
export function isAllMockBuild(): boolean {
  return isMockAccountEntryAllowed(buildTimeEnvironment())
}

/** TODO(real-api): 验收门打开后以同名 POST 实现替换占位，不改变页面调用签名。 */
export function realAdapterPending(operation: string, endpoint: string): never {
  throw new ContractError(
    'REAL_ADAPTER_PENDING',
    `${operation} 尚未接真实接口（计划 POST ${endpoint}）`,
  )
}
