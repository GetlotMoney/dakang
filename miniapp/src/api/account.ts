import type { EntityId } from './common'
import { normalizeAccountContext } from './auth'
import { ContractError } from './common'
import { getToken, post } from './request'

/**
 * 账号上下文契约。
 *
 * 本文件只承载类型与会话恢复：真实会话由正式微信登录动线（authApi）建立并写入 accountStore。
 * 冷启动恢复走 POST /mini/auth/context（只读、不换发会话）；无 token 或 token 失效时
 * fail-closed 交回登录入口，绝不伪造降级会话。
 */

export type CapabilityCode
  = | 'USER_BASE'
    | 'COURIER_APPLY'
    | 'COURIER_WORK'
    | 'OWNER_VIEW'
    | 'OWNER_SERVICE'
    | 'CHANNEL_VIEW'
    | 'REGION_VIEW'

export type CourierStatus = 1 | 2 | 3 | 4

export interface CourierScope {
  courierId: EntityId
  status: CourierStatus
  stationIds: EntityId[]
  serviceRegion?: string
}

export interface OwnerScope {
  stationIds: EntityId[]
  deviceNos: string[]
}

export interface AccountContext {
  accountId: EntityId
  userId: EntityId
  userName: string
  /** 未绑手机号时缺省（仅微信身份建号）。取子串前必须先看 phoneBound，别直接 slice。 */
  userPhone?: string
  /** 是否已绑手机号；未绑时展示补绑入口。 */
  phoneBound: boolean
  /** 头像的服务端相对路径；未设置缺省，展示占位图标。经 resolveServerPath 拼全地址。 */
  userAvatar?: string
  capabilities: CapabilityCode[]
  courierScope?: CourierScope
  ownerScope?: OwnerScope
}

export const accountEndpoints = {
  context: '/mini/auth/context',
} as const

export const accountApi = {
  /**
   * 冷启动会话恢复：拿本地已存的 token 向服务端换回账号上下文。本地无 token 直接 fail-closed
   * 不发请求（必然 1401，还会触发全局登出清一份不存在的会话）；token 失效由 request.ts
   * 会话代际判定处理，本函数只把失败原样抛出。
   */
  async restoreSession(): Promise<AccountContext> {
    if (!getToken()) {
      throw new ContractError('SESSION_NOT_FOUND', '会话未建立，请从入口登录')
    }
    const raw = await post<Record<string, unknown>>(accountEndpoints.context, {})
    return normalizeAccountContext(raw as never)
  },
}
