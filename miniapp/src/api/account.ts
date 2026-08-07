import type { EntityId } from './common'
import { ContractError } from './common'

/**
 * 账号上下文契约（2026-08-02 mock 基建退役后收敛版）。
 *
 * 本文件只承载类型与最小会话恢复语义：真实会话由登录动线（authApi / test-login）建立并写入
 * accountStore；后端没有「读当前会话上下文」端点，冷启动恢复一律 fail-closed 交回登录入口，
 * 绝不伪造降级会话。原型账号库与场景重置能力已随 scenario 场景库整体退役。
 */

export type CapabilityCode
  = | 'USER_BASE'
    | 'COURIER_APPLY'
    | 'COURIER_WORK'
    | 'OWNER_VIEW'
    | 'OWNER_SERVICE'

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

export const accountApi = {
  /**
   * 冷启动会话恢复：无服务端读会话端点，恒 fail-closed。
   * 调用方（App 根组件 / 页面 onShow）catch 后交由统一入口重新登录。
   */
  async restoreSession(): Promise<AccountContext> {
    throw new ContractError('SESSION_NOT_FOUND', '会话未建立，请从入口登录')
  },
}
