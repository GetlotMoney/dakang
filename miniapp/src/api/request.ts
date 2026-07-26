import type { ApiEnvelope } from './common'
import { ContractError } from './common'

/**
 * 小程序真实接口请求层（uni.request 封装）。
 *
 * - 基址读 VITE_SERVER_BASEURL（直连后端，不经代理，见 CH-6 L1a-OPS 结论）；
 * - 会话头名 dakang-token（后端 sa-token token-name）；
 * - 统一 POST + JSON；成功码 code===0 取 data；
 * - 后端整数拒绝码按映射转成小程序 ContractError 字符码；
 * - 仅承载真实 HTTP，不含任何 Mock 逻辑与业务规则。
 */

/** 后端 sa-token 会话头名默认值（KH_USER token-name）；实际以登录响应返回的 tokenName 为准。 */
const DEFAULT_AUTH_HEADER = 'dakang-token'
/** 本地缓存会话 token 的 storage key。 */
const TOKEN_STORAGE_KEY = 'dakang-mini-token'
/** 本地缓存会话头名（严格取自登录响应的 tokenName，前端按此原样回传）。 */
const TOKEN_NAME_STORAGE_KEY = 'dakang-mini-token-name'

const BASE_URL = import.meta.env.VITE_SERVER_BASEURL

/** 后端未登录业务码（sa-token 经 R 包装后返回）。 */
export const UNAUTHORIZED_CODE = 1401
/**
 * 会话失效业务码集合（与后端 ErrorMsg.java 一致）：
 * 1401 登录状态异常、1402 登录超时、1403 被顶下线、1404 被踢下线、1405 账户冻结。
 * 任一命中都代表正式会话不可用，统一按 UNAUTHORIZED 处理并一次性登出。
 */
export const SESSION_INVALID_CODES: ReadonlySet<number> = new Set([1401, 1402, 1403, 1404, 1405])

/** 全局会话失效处理器（清会话 + 回登录入口）；由 App 启动时注册一次，任一 140x 命中即触发。 */
let sessionInvalidHandler: (() => void) | null = null

export function onSessionInvalid(handler: () => void): void {
  sessionInvalidHandler = handler
}

/**
 * 后端整数拒绝码 → 小程序 ContractError 字符码映射（L0 v1.1 / 团队记忆 mem_mrrvcvzw）。
 * 仅登记会以“拒绝码”返回的场景；成功 code=0；
 * eligibility 的 availability(DeviceAvailability) 与 cardBlock 是正常返回数据、不在此列。
 */
const REJECT_CODE_MAP: Record<number, string> = {
  5401: 'INVALID_QR_CODE',
  5402: 'QR_EXPIRED',
  5403: 'UNIVERSAL_CODE_PENDING',
  5410: 'QR_EXPIRED',
}

export function getToken(): string {
  return uni.getStorageSync(TOKEN_STORAGE_KEY) || ''
}

export function setToken(token: string): void {
  uni.setStorageSync(TOKEN_STORAGE_KEY, token)
}

/** 会话头名：严格取自登录响应的 tokenName（缺省回退默认值），保证与服务端 sa-token token-name 一致。 */
export function getTokenName(): string {
  return uni.getStorageSync(TOKEN_NAME_STORAGE_KEY) || DEFAULT_AUTH_HEADER
}

export function setTokenName(name: string): void {
  if (name) {
    uni.setStorageSync(TOKEN_NAME_STORAGE_KEY, name)
  }
}

/** 清除本端正式会话（token + 头名）；401 与退出登录使用，绝不回退 Mock。 */
export function clearToken(): void {
  uni.removeStorageSync(TOKEN_STORAGE_KEY)
  uni.removeStorageSync(TOKEN_NAME_STORAGE_KEY)
}

function rawPost<T>(path: string, data: Record<string, unknown>, sessionToken: string): Promise<ApiEnvelope<T>> {
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${BASE_URL}${path}`,
      method: 'POST',
      data,
      header: {
        'Content-Type': 'application/json',
        [getTokenName()]: sessionToken,
      },
      timeout: 15000,
      success: (res) => {
        const body = res.data as ApiEnvelope<T>
        if (!body || typeof body.code !== 'number') {
          reject(new ContractError('NETWORK_ERROR', '服务响应格式异常'))
          return
        }
        resolve(body)
      },
      fail: () => reject(new ContractError('NETWORK_ERROR', '网络异常，请稍后重试')),
    })
  })
}

/**
 * 统一 POST：成功(code=0)返回 data；会话失效(1401~1405)先触发全局一次性登出再抛 UNAUTHORIZED（不重试、不续用旧会话）；
 * 已登记拒绝码转对应 ContractError 字符码；其余按通用失败（携带后端 msg）。
 *
 * 会话代际判定（E2E-03 验收 P1-5）：发起时冻结本请求实际携带的 token 作为「代际」标识；
 * 收到会话失效码时，只有该 token 仍是当前会话（失败响应对应的会话未被更替）才执行全局登出。
 * 否则丢弃登出动作、仅向调用方抛错——旧页面滞留请求的 1401 在新登录建立后迟到时，
 * 不得清掉新会话（实测复现：显示新用户但数据空、token 被清、再登录才恢复）。
 * 当前会话的 1401~1405 清会话语义不变，只加这一道代际判定。
 */
export async function post<T>(path: string, data: Record<string, unknown> = {}): Promise<T> {
  const sessionToken = getToken()
  const body = await rawPost<T>(path, data, sessionToken)
  if (body.code === 0) {
    return body.data
  }
  if (SESSION_INVALID_CODES.has(body.code)) {
    // 任一会话失效码：仅当失败响应对应的会话仍是当前会话，才触发全局失效处理
    // （清会话 + 回登录入口）；随后统一抛 UNAUTHORIZED，绝不带着失效会话继续。
    if (sessionInvalidHandler && sessionToken === getToken()) {
      sessionInvalidHandler()
    }
    throw new ContractError('UNAUTHORIZED', body.msg || '登录状态异常，请重新登录')
  }
  const mappedCode = REJECT_CODE_MAP[body.code]
  throw new ContractError(mappedCode ?? 'REQUEST_FAILED', body.msg || `请求失败（${body.code}）`)
}
