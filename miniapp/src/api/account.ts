import type { EntityId } from './common'
import { cloneContractData, ContractError } from './common'
import { realAdapterPending, selectAdapter } from './runtime'
import { scenarioStore } from '@/scenario/store'

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
  userPhone: string
  capabilities: CapabilityCode[]
  courierScope?: CourierScope
  ownerScope?: OwnerScope
}

export type EntryStage
  = | 'UNAUTHENTICATED'
    | 'PRIVACY_REQUIRED'
    | 'PHONE_REQUIRED'
    | 'READY'
    | 'AUTHORIZATION_FAILED'

export interface EntryContractState {
  stage: EntryStage
  context?: AccountContext
  wechatLoginCapability: 'unavailable' | 'denied' | 'canceled' | 'success'
  phoneBindingCapability: 'unavailable' | 'denied' | 'canceled' | 'success'
  evidenceMode: 'prototype' | 'external-snapshot' | 'real'
}

/** C01 开发适配器展示用的账号摘要；生产适配器不提供。 */
export interface MockAccountSummary {
  accountId: EntityId
  userName: string
  userPhone: string
  capabilities: CapabilityCode[]
}

export interface AccountApi {
  getEntryState: () => Promise<EntryContractState>
  restoreSession: () => Promise<AccountContext>
  getContext: () => Promise<AccountContext>
  selectMockAccount: (accountId: EntityId) => Promise<AccountContext>
  /** 仅 Mock：列出可切换的原型账号，供 C01 开发适配器多账号验收（S02/S06）。 */
  listMockAccounts: () => Promise<MockAccountSummary[]>
  /** 仅 Mock：模拟 PC 侧停用/恢复配送员，演示能力撤销后的入口与页面降级（S01.5/S02.5）。 */
  setMockCourierWorkEnabled: (accountId: EntityId, enabled: boolean) => Promise<AccountContext>
  /** 仅 Mock：把原型数据恢复到初始快照（演示复位与自动化幂等入口）。 */
  resetMockScenario: () => Promise<AccountContext>
}

export const accountEndpoints = {
  entryState: '/mini/account/entry-state',
  context: '/mini/account/context',
} as const

function activeContext() {
  const context = scenarioStore.accounts.find(
    item => item.accountId === scenarioStore.activeAccountId,
  )
  if (!context) {
    throw new ContractError('SESSION_NOT_FOUND', '当前原型会话不存在')
  }
  return cloneContractData(context)
}

const mockAccountApi: AccountApi = {
  async getEntryState() {
    return {
      stage: 'READY',
      context: activeContext(),
      wechatLoginCapability: 'unavailable',
      phoneBindingCapability: 'unavailable',
      evidenceMode: 'prototype',
    }
  },
  async restoreSession() {
    return activeContext()
  },
  async getContext() {
    return activeContext()
  },
  async selectMockAccount(accountId) {
    scenarioStore.selectAccount(accountId)
    return activeContext()
  },
  async listMockAccounts() {
    return cloneContractData(
      scenarioStore.accounts.map(item => ({
        accountId: item.accountId,
        userName: item.userName,
        userPhone: item.userPhone,
        capabilities: item.capabilities,
      })),
    )
  },
  async setMockCourierWorkEnabled(accountId, enabled) {
    scenarioStore.setCourierWorkEnabled(accountId, enabled)
    return activeContext()
  },
  async resetMockScenario() {
    scenarioStore.reset()
    return activeContext()
  },
}

const realAccountApi: AccountApi = {
  async getEntryState() {
    return realAdapterPending('查询统一入口状态', accountEndpoints.entryState)
  },
  async restoreSession() {
    return realAdapterPending('恢复账号会话', accountEndpoints.context)
  },
  async getContext() {
    return realAdapterPending('查询账号能力', accountEndpoints.context)
  },
  async selectMockAccount() {
    throw new ContractError('MOCK_ONLY', '生产适配器不支持切换原型账号')
  },
  async listMockAccounts() {
    throw new ContractError('MOCK_ONLY', '生产适配器不提供原型账号列表')
  },
  async setMockCourierWorkEnabled() {
    throw new ContractError('MOCK_ONLY', '生产适配器不支持模拟能力停用')
  },
  async resetMockScenario() {
    throw new ContractError('MOCK_ONLY', '生产适配器不支持重置原型数据')
  },
}

export const accountApi = selectAdapter(mockAccountApi, realAccountApi)
