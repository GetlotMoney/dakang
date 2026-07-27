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

const RESULT_BOUND = 'BOUND'
const RESULT_UNBOUND = 'UNBOUND'

/** 后端 accountContext 原样结构（字段均视为不可信，运行时逐项校验）。 */
interface RawAccountContext {
  accountId: unknown
  userId: unknown
  userName: unknown
  userPhone: unknown
  capabilities?: unknown
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
    | { authorized: false }

/**
 * 解析 getPhoneNumber 回调 detail：仅当返回 phoneCode 才视为已授权可绑定；
 * 拒绝/取消（无 code）判为未授权——上层据此提示重试且不消费一次性 bindTicket。
 */
export function readPhoneAuthorization(detail?: { code?: string, errMsg?: string }): PhoneAuthorization {
  const phoneCode = detail?.code
  return phoneCode ? { authorized: true, phoneCode } : { authorized: false }
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
          reject(new ContractError('WECHAT_LOGIN_FAILED', '微信登录未返回 code，请重试'))
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
    throw new ContractError('AUTH_CONTRACT_BROKEN', `账号上下文 ${field} 非法：必须为十进制字符串 ID`)
  }
  return value as EntityId
}

function requireString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new ContractError('AUTH_CONTRACT_BROKEN', `账号上下文 ${field} 非法：必须为非空字符串`)
  }
  return value
}

function normalizeContext(raw: RawAccountContext): AccountContext {
  return {
    accountId: requireDecimalStringId(raw.accountId, 'accountId'),
    userId: requireDecimalStringId(raw.userId, 'userId'),
    userName: requireString(raw.userName, 'userName'),
    userPhone: requireString(raw.userPhone, 'userPhone'),
    capabilities: Array.isArray(raw.capabilities) ? [...(raw.capabilities as CapabilityCode[])] : [],
  }
}

/**
 * BOUND 分支落地：严格按响应 tokenName 写会话头名 + tokenValue，返回规范化 AccountContext。
 * 缺 token 或 accountContext 视为契约破坏，抛错而非静默降级。
 */
function applyBoundSession(raw: RawAuthResult): AccountContext {
  if (!raw.tokenName || !raw.tokenValue || !raw.accountContext) {
    throw new ContractError('AUTH_CONTRACT_BROKEN', '登录响应缺少会话票据或账号上下文')
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
    throw new ContractError('AUTH_CONTRACT_BROKEN', '测试登录未建立会话')
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
        throw new ContractError('AUTH_CONTRACT_BROKEN', '登录响应缺少绑定票据')
      }
      return {
        stage: 'UNBOUND',
        bindTicket: raw.bindTicket,
        expiresInSeconds: raw.expiresInSeconds ?? 0,
      }
    }
    throw new ContractError('AUTH_CONTRACT_BROKEN', `未知登录结果类型：${raw.result}`)
  },

  /**
   * 绑定手机号：以一次性 bindTicket + getPhoneNumber 的 phoneCode 建会话。
   * 成功必为 BOUND；票据一次性，失败（含票据失效）需重新 login，前端不得复用旧票据。
   */
  async bindPhone(bindTicket: string, phoneCode: string): Promise<AccountContext> {
    const raw = await post<RawAuthResult>(BIND_PHONE_ENDPOINT, { bindTicket, phoneCode })
    if (raw.result !== RESULT_BOUND) {
      throw new ContractError('AUTH_CONTRACT_BROKEN', '绑定手机号未建立会话')
    }
    return applyBoundSession(raw)
  },

  /** 清除本端正式会话（退出登录 / 401 使用）；绝不回退 Mock 账号。 */
  clearSession(): void {
    clearToken()
  },
}
