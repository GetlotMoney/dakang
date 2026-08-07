import type { AccountContext, CapabilityCode } from './account'
import type { EntityId } from './common'
import { ContractError } from './common'
import { clearToken, post, setToken, setTokenName } from './request'

/**
 * 小程序 L2-AUTH 正式登录适配层（真实微信静默登录 + 手机号绑定）。
 *
 * 流程：uni.login 取一次性 code → POST /mini/auth/login。
 *   - BOUND：写入 tokenName+tokenValue 会话，返回 AccountContext 进首页；
 *   - UNBOUND：拿到一次性 bindTicket，用户 getPhoneNumber 授权后 POST /mini/auth/bind-phone 建会话。
 * 全程不接收 openid/session_key；BOUND 分支才写会话；失败/401 由上层 clearSession 清理，绝不回退 Mock。
 * Long 型 ID 全程按字符串处理（后端已 String 序列化，这里再做防御性 String()）。
 */

const LOGIN_ENDPOINT = '/mini/auth/login'
const BIND_PHONE_ENDPOINT = '/mini/auth/bind-phone'
const BIND_PHONE_SELF_ENDPOINT = '/mini/auth/bind-phone-self'

const RESULT_BOUND = 'BOUND'
const RESULT_UNBOUND = 'UNBOUND'

/** 后端 accountContext 原样结构（字段均视为不可信，运行时逐项校验）。 */
interface RawAccountContext {
  accountId: unknown
  userId: unknown
  userName: unknown
  userPhone?: unknown
  phoneBound?: unknown
  userAvatar?: unknown
  capabilities?: unknown
  ownerScope?: unknown
}

/** 后端 MiniAuthResultVo 原样结构；绝不含 openid / session_key。 */
interface RawAuthResult {
  result: string
  tokenName?: string
  tokenValue?: string
  accountContext?: RawAccountContext
  bindTicket?: string
  expiresInSeconds?: number
}

/** 登录结果的判别联合：BOUND 已建会话；UNBOUND 待绑手机号。 */
export type AuthLoginResult
  = | { stage: 'BOUND', context: AccountContext }
    | { stage: 'UNBOUND', bindTicket: string, expiresInSeconds: number }

/** getPhoneNumber 回调判别：有 code=已授权待绑定；无 code=用户拒绝/取消（可恢复，保留票据重试）。 */
export type PhoneAuthorization
  = | { authorized: true, phoneCode: string }
    | { authorized: false, reason?: string }

/**
 * 解析 getPhoneNumber 回调 detail：仅当返回 phoneCode 才视为已授权可绑定；
 * 拒绝/取消（无 code）判为未授权——上层据此提示重试且不消费一次性 bindTicket。
 */
export function readPhoneAuthorization(detail?: { code?: string, errMsg?: string }): PhoneAuthorization {
  const phoneCode = detail?.code
  if (phoneCode) {
    return { authorized: true, phoneCode }
  }
  // 未拿到 code：把微信原始 errMsg 带出去。用户主动拒绝与平台侧不可用
  // （未开通手机号验证组件、额度用尽、主体未认证）文案完全不同，
  // 吞掉 errMsg 会让「点了没反应」和「余额不足」看起来一模一样，无法定位。
  return { authorized: false, reason: detail?.errMsg }
}

/** uni.login 取一次性登录 code（Promise 化）；失败抛 WECHAT_LOGIN_FAILED，可重试。 */
function fetchWechatLoginCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.login({
      provider: 'weixin',
      success: (res) => {
        if (res.code) {
          resolve(res.code)
        }
        else {
          reject(new ContractError('WECHAT_LOGIN_FAILED', '微信登录未完成，请重试'))
        }
      },
      fail: () => reject(new ContractError('WECHAT_LOGIN_FAILED', '微信登录失败，请重试')),
    })
  })
}

/** 十进制正整数字符串（Long PK ≥1，无前导零、无符号、无小数/指数）。 */
const DECIMAL_ID_PATTERN = /^[1-9]\d*$/

/**
 * Long 型 ID 只接受十进制字符串。
 * 若后端把超过 2^53 的 Long 当 JSON number 返回，精度在解析时已丢失、String() 无法恢复，
 * 因此 number / 空串 / 负数 / 小数 / 指数 / 非数字一律判为契约破坏（AUTH_CONTRACT_BROKEN），绝不转换后放行。
 */
function requireDecimalStringId(value: unknown, field: string): EntityId {
  if (typeof value !== 'string' || !DECIMAL_ID_PATTERN.test(value)) {
    throw new ContractError('AUTH_CONTRACT_BROKEN', `登录信息异常（${field}），请重新登录`)
  }
  return value as EntityId
}

function requireString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new ContractError('AUTH_CONTRACT_BROKEN', `登录信息异常（${field}），请重新登录`)
  }
  return value
}

/**
 * 可选字符串：缺省/空串归一为 undefined，其余类型判契约破坏。
 * 手机号在「仅微信身份建号」下本就可以没有，用 requireString 会把正常登录直接判成契约破坏。
 */
function optionalString(value: unknown, field: string): string | undefined {
  if (value === undefined || value === null || value === '') {
    return undefined
  }
  if (typeof value !== 'string') {
    throw new ContractError('AUTH_CONTRACT_BROKEN', `登录信息异常（${field}），请重新登录`)
  }
  return value
}

function normalizeContext(raw: RawAccountContext): AccountContext {
  // E2E-06：机主授权范围随登录上下文下发（profile 展示用；缺省/畸形一律不带，不构成授权）
  const scopeRaw = raw.ownerScope as { stationIds?: unknown, deviceNos?: unknown } | undefined
  const ownerScope = scopeRaw
    && Array.isArray(scopeRaw.stationIds) && Array.isArray(scopeRaw.deviceNos)
    ? {
        stationIds: (scopeRaw.stationIds as unknown[]).map(String),
        deviceNos: (scopeRaw.deviceNos as unknown[]).map(String),
      }
    : undefined
  const userPhone = optionalString(raw.userPhone, 'userPhone')
  return {
    accountId: requireDecimalStringId(raw.accountId, 'accountId'),
    userId: requireDecimalStringId(raw.userId, 'userId'),
    userName: requireString(raw.userName, 'userName'),
    userPhone,
    // 判据只认号码本身，不认后端的 phoneBound 声明。
    // 「顶部显示号码」与「我的页显示补绑入口」是同一件事的两面，两者必须同真同假：
    // 若信了 phoneBound=true 而号码实际缺失（后端脏数据或版本错配），就会出现
    // 「首页写着未绑手机号、我的页却找不到补绑入口」的死角，用户永远绑不上。
    // 后端字段保留作契约自文档与将来扩展位，此处不参与判定。
    phoneBound: Boolean(userPhone),
    userAvatar: optionalString(raw.userAvatar, 'userAvatar'),
    capabilities: Array.isArray(raw.capabilities) ? [...(raw.capabilities as CapabilityCode[])] : [],
    ownerScope,
  }
}

/** 供资料更新等「返回裸 accountContext」的端点复用同一套规范化校验。 */
export function normalizeAccountContext(raw: RawAccountContext): AccountContext {
  return normalizeContext(raw)
}

/**
 * BOUND 分支落地：严格按响应 tokenName 写会话头名 + tokenValue，返回规范化 AccountContext。
 * 缺 token 或 accountContext 视为契约破坏，抛错而非静默降级。
 */
function applyBoundSession(raw: RawAuthResult): AccountContext {
  if (!raw.tokenName || !raw.tokenValue || !raw.accountContext) {
    throw new ContractError('AUTH_CONTRACT_BROKEN', '登录失败，请重试')
  }
  // 先完整校验并规范化上下文；任一字段非法即抛错，绝不先落 token 再留下孤立会话（复审 P1-5）。
  const context = normalizeContext(raw.accountContext)
  setTokenName(raw.tokenName)
  setToken(raw.tokenValue)
  return context
}

/**
 * 测试登录（仅测试环境，与后端 Pay-Sim 同一开关门控）。
 *
 * 存在的唯一理由：小程序主体的 appid/appsecret 未配置，真机上 `uni.login` 走不通，
 * 于是所有接真业务域一个都点不到。它建立的是**真实 KH_USER 会话**——
 * 与正式登录落地的是同一份 token，因此后续所有接口走的都是真链路，
 * 与「Mock 原型账号打真实接口」有本质区别。
 *
 * 只在 `VITE_TEST_LOGIN_PHONE` 被显式配置时才可能被调用；交付构建不配置该变量。
 */
export const testLoginPhone: string | undefined = import.meta.env.VITE_TEST_LOGIN_PHONE

/** 显式退出登录后要求入口停在测试账号选择的标记（仅测试登录模式写入/消费）。 */
export const TEST_LOGIN_SWITCH_KEY = 'dakang-test-login-switch'

export interface TestLoginAccount {
  phone: string
  label: string
}

/**
 * 解析「手机号:标签」逗号清单为测试账号白名单（仅测试环境构建配置 VITE_TEST_LOGIN_PHONES）。
 * 非法手机号一律丢弃——白名单是构建期写死的，不提供自由输入，杜绝把测试入口变成账号枚举面。
 * 未配置时回退单号 VITE_TEST_LOGIN_PHONE，旧构建行为不变（入口自动登录、无选择页）。
 */
export function parseTestLoginAccounts(raw: string | undefined, fallbackPhone?: string): TestLoginAccount[] {
  const parsed = (raw ?? '')
    .split(',')
    .map(item => item.trim())
    .filter(item => item.length > 0)
    .map((item) => {
      const [phone = '', label = ''] = item.split(':').map(part => part.trim())
      return { phone, label: label || phone }
    })
    .filter(account => /^1\d{10}$/.test(account.phone))
  if (parsed.length > 0) {
    return parsed
  }
  return fallbackPhone ? [{ phone: fallbackPhone, label: fallbackPhone }] : []
}

/** 测试账号白名单；入口自动登录仍用 testLoginPhone，本清单只服务显式退出后的切换。 */
export const testLoginAccounts: TestLoginAccount[] = parseTestLoginAccounts(
  import.meta.env.VITE_TEST_LOGIN_PHONES,
  testLoginPhone,
)

export async function loginByTestPhone(phone: string): Promise<AccountContext> {
  const raw = await post<RawAuthResult>('/mini/test-login/by-phone', { phone })
  if (raw.result !== RESULT_BOUND) {
    throw new ContractError('AUTH_CONTRACT_BROKEN', '登录失败，请重试')
  }
  return applyBoundSession(raw)
}

export const authApi = {
  /** 静默登录：BOUND 直接建会话进首页；UNBOUND 返回一次性绑定票据。 */
  async login(): Promise<AuthLoginResult> {
    const code = await fetchWechatLoginCode()
    const raw = await post<RawAuthResult>(LOGIN_ENDPOINT, { code })
    if (raw.result === RESULT_BOUND) {
      return { stage: 'BOUND', context: applyBoundSession(raw) }
    }
    if (raw.result === RESULT_UNBOUND) {
      if (!raw.bindTicket) {
        throw new ContractError('AUTH_CONTRACT_BROKEN', '登录失败，请重试')
      }
      return {
        stage: 'UNBOUND',
        bindTicket: raw.bindTicket,
        expiresInSeconds: raw.expiresInSeconds ?? 0,
      }
    }
    throw new ContractError('AUTH_CONTRACT_BROKEN', '登录失败，请重试')
  },

  /**
   * 绑定手机号：以一次性 bindTicket + getPhoneNumber 的 phoneCode 建会话。
   * 成功必为 BOUND；票据一次性，失败（含票据失效）需重新 login，前端不得复用旧票据。
   */
  async bindPhone(bindTicket: string, phoneCode: string): Promise<AccountContext> {
    const raw = await post<RawAuthResult>(BIND_PHONE_ENDPOINT, { bindTicket, phoneCode })
    if (raw.result !== RESULT_BOUND) {
      throw new ContractError('AUTH_CONTRACT_BROKEN', '手机号绑定失败，请重试')
    }
    return applyBoundSession(raw)
  },

  /**
   * 登录后自助补绑手机号（仅微信身份建号的账号用）。
   *
   * 与 {@link bindPhone} 的区别：由已有会话授权，不需要 bindTicket，也不换发 token——
   * 后端只回吐刷新后的账号上下文，前端就地替换，用户不会在补绑成功后被踢回登录页。
   */
  async bindPhoneSelf(phoneCode: string): Promise<AccountContext> {
    const raw = await post<RawAccountContext>(BIND_PHONE_SELF_ENDPOINT, { phoneCode })
    return normalizeContext(raw)
  },

  /** 清除本端正式会话（退出登录 / 401 使用）；绝不回退 Mock 账号。 */
  clearSession(): void {
    clearToken()
  },
}
