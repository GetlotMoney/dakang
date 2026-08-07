import { ContractError } from './common'

/** 全局 API 模式（未按域覆盖时的回退值）。 */
export const apiMode = import.meta.env.VITE_API_MODE

/**
 * 支持按域覆盖的业务域标识。新增域接真时在此登记，并在 domainOverrideMode 增加对应静态读取。
 * 目前 device 域（扫码取水链，L1a-MP）、order 域（下单/订单/详情，L1e-MP）、card 域（水卡余额，L1f-MP）已接真；
 * recharge 域（L2 充值链）自 L2-T 起后端已实现（创单→支付事实→入账），代码层硬锁解除，按域覆盖判定；
 * auth 域（L2-AUTH 正式微信登录/绑手机）默认 mock（走 C01 原型入口），仅在显式 Real 入口构建下翻 real；
 * delivery 域（E2E-03 水配送链，包B）后端 /mini/delivery/** 已实现，按域覆盖判定——
 * 与 recharge 域同一先例：只有显式 real 才接真，漏配回落 mock；
 * message 域（E2E-07 消息中心）后端 /mini/message/** 已实现，同一先例。
 */
export type ApiDomain = 'device' | 'order' | 'card' | 'recharge' | 'auth' | 'delivery' | 'message'

/** 模式解析的环境快照（纯函数入参，测试可直接构造任意组合，含全局 real）。 */
export interface ApiModeEnvironment {
  globalMode?: string
  deviceMode?: string
  orderMode?: string
  cardMode?: string
  rechargeMode?: string
  authMode?: string
  deliveryMode?: string
  messageMode?: string
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
                : domain === 'message'
                  ? env.messageMode
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
    messageMode: import.meta.env.VITE_API_MODE_MESSAGE,
  }
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
    `MESSAGE=${currentMode('message')}`,
  ].join(';')
}

/**
 * 微信「手机号快速验证」组件是否可用。
 *
 * 小程序主体未通过微信认证时，平台在**组件层**禁用 `open-type="getPhoneNumber"`：
 * 点击不弹窗、`@getphonenumber` 回调根本不触发——连错误分支都跑不到，按钮表现为完全没反应。
 * 因此不能把这种情况留给「点了再看回调」，只能在构建物里写死是否渲染该按钮。
 *
 * 【为什么是代码常量而不是环境变量】2026-08-01 产物污染事故：多个残留 watch 编译器各持启动时刻的
 * env 快照竞写同一 dist，环境变量注入的值在产物里不可判定；且「注入失败/取到旧值」与「关闭态」
 * 表现完全一致，故障不会立刻暴露，会潜伏到要开放绑定那天才爆。代码常量没有注入环节，
 * 产物值恒等于源码值，从机制上免疫整类问题。主体完成微信认证后，把下面的值改为 true 即放开绑定入口。
 */
export const WX_PHONE_COMPONENT_AVAILABLE = false

export function isPhoneComponentAvailable(): boolean {
  return WX_PHONE_COMPONENT_AVAILABLE
}

/**
 * TODO(real-api): 验收门打开后以同名 POST 实现替换占位，不改变页面调用签名。
 *
 * <p>message 只说「用户能不能用」，规划中的 endpoint 不进这句话——它会被页面
 * 直接 toast 给用户，而用户既看不懂 POST 路径，也不该知道我们的排期。
 * endpoint 仍作为入参保留：调用点写明规划路径，排障时看代码即可定位，
 * 配合错误码 REAL_ADAPTER_PENDING 足够，不必印在界面上。</p>
 */
export function realAdapterPending(operation: string, endpoint: string): never {
  void endpoint
  throw new ContractError('REAL_ADAPTER_PENDING', `${operation}暂不可用`)
}
