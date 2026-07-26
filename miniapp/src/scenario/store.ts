import type { CapabilityCode } from '@/api/account'
import type { ScanSession } from '@/api/device'
import type { MessageItem } from '@/api/message'
import type { ScenarioState } from './fixtures'
import { reactive } from 'vue'
import { cloneContractData, ContractError, nextBusinessTime } from '@/api/common'
import { initialScenario } from './fixtures'

const state = reactive<ScenarioState>(cloneContractData(initialScenario))

export const scenarioStore = {
  get now() {
    return state.now
  },
  /**
   * 统一逻辑时钟（2026-07-18 第四轮审计整改）：业务动作取得动作时间后单调推进场景当前时间，
   * 后发生的动作不会再拿到更早的时间；重置原型数据时随场景整体还原，保持可复现。
   */
  advanceBusinessClock(time: string) {
    if (time > state.now) {
      state.now = time
    }
  },
  /**
   * 统一逻辑时钟取数（第五轮审计整改）：动作时间 = max(当前时钟, 各业务下限) + 偏移，
   * 取值后立即推进时钟并返回。必须在动作全部校验通过后调用——拒绝路径不得推进时钟。
   */
  takeBusinessTime(floorTimes: Array<string | undefined> = [], offsetSeconds = 1): string {
    const time = nextBusinessTime({
      floorTimes: [state.now, ...floorTimes],
      offsetSeconds,
    })
    if (time > state.now) {
      state.now = time
    }
    return time
  },
  get activeAccountId() {
    return state.activeAccountId
  },
  get accounts() {
    return state.accounts
  },
  get waterTypes() {
    return state.waterTypes
  },
  get stations() {
    return state.stations
  },
  get packages() {
    return state.packages
  },
  get devices() {
    return state.devices
  },
  get ownerServiceRequests() {
    return state.ownerServiceRequests
  },
  get cards() {
    return state.cards
  },
  get addresses() {
    return state.addresses
  },
  get familyProfiles() {
    return state.familyProfiles
  },
  get familyRewardRecords() {
    return state.familyRewardRecords
  },
  get orderDetails() {
    return state.orderDetails
  },
  get deliveryTasks() {
    return state.deliveryTasks
  },
  get courierAdmissions() {
    return state.courierAdmissions
  },
  get deliveryExceptions() {
    return state.deliveryExceptions
  },
  get deliveryAppeals() {
    return state.deliveryAppeals
  },
  get messages() {
    return state.messages
  },
  get scanSession() {
    return state.scanSession
  },
  get scanSessions() {
    return state.scanSessions
  },
  get waterEligibility() {
    return state.waterEligibility
  },
  get auditEvents() {
    return state.auditEvents
  },
  activeAccount() {
    const context = state.accounts.find(item => item.accountId === state.activeAccountId)
    if (!context) {
      throw new ContractError('SESSION_NOT_FOUND', '当前原型账号不存在')
    }
    return context
  },
  selectAccount(accountId: string) {
    if (!state.accounts.some(item => item.accountId === accountId)) {
      throw new ContractError('ACCOUNT_NOT_FOUND', '原型账号不存在')
    }
    state.activeAccountId = accountId
  },
  nextOrderSequence() {
    const current = String(state.orderSequence).padStart(4, '0')
    state.orderSequence += 1
    return current
  },
  /** 充值 Mock 请求序号属于场景状态，随 C01“重置原型数据”一并还原。 */
  nextRechargeRequestSequence() {
    const current = state.rechargeRequestSequence
    state.rechargeRequestSequence += 1
    return current
  },
  /** 注册扫码适配器产生的短期会话；会话 ID 由固定序号推导，保证演示可复现。 */
  registerScanSession(deviceNo: string, outletId: string): ScanSession {
    const session: ScanSession = {
      scanSessionId: `SCAN-SESSION-${String(state.scanSequence).padStart(4, '0')}`,
      deviceNo,
      outletId,
      expiresAt: '20260716190000',
    }
    state.scanSequence += 1
    state.scanSessions.push(session)
    return session
  },
  /** 查找未消费且未过期的扫码会话；找不到即视为已失效。 */
  findActiveScanSession(scanSessionId: string): ScanSession | undefined {
    if (state.consumedScanSessionIds.includes(scanSessionId)) {
      return undefined
    }
    const session = [state.scanSession, ...state.scanSessions].find(
      item => item.scanSessionId === scanSessionId,
    )
    if (!session || session.expiresAt < state.now) {
      return undefined
    }
    return session
  },
  /** 下单成功即消费会话，二次使用按已失效拒绝（蓝图 §6.6：消费后失效）。 */
  consumeScanSession(scanSessionId: string) {
    if (!state.consumedScanSessionIds.includes(scanSessionId)) {
      state.consumedScanSessionIds.push(scanSessionId)
    }
  },
  /** 原型动作产生的站内消息，插到列表头部；ID 由固定序号推导。 */
  pushMessage(message: Omit<MessageItem, 'messageId'>) {
    state.messages.unshift({
      ...message,
      messageId: `MSG-M${state.messageSequence}`,
    })
    state.messageSequence += 1
  },
  recordAudit(
    actingCapability: CapabilityCode,
    action: string,
    objectType: string,
    objectId: string,
    result: 'success' | 'rejected',
    businessTime?: string,
  ) {
    // 有业务动作时间的审计与动作同源（第四轮统一逻辑时钟）；
    // 无关联业务时间的普通审计也从逻辑时钟派生并推进时钟，任何审计不得早于当前场景时间（第五轮审计整改）。
    let time = businessTime
    if (!time) {
      time = nextBusinessTime({ floorTimes: [state.now], offsetSeconds: 1 })
      state.now = time
    }
    state.auditEvents.push({
      auditId: `MA-${state.auditSequence}`,
      accountId: state.activeAccountId,
      actingCapability,
      portal: 'miniapp',
      action,
      objectType,
      objectId,
      time,
      result,
    })
    state.auditSequence += 1
  },
  /**
   * 开发适配器专用：模拟 PC 侧启用/停用配送员，用于 S01.5/S02.5 能力撤销演示。
   * 只影响 COURIER_WORK 投影与准入状态，不触碰其他能力，也不代表真实审核动作。
   */
  setCourierWorkEnabled(accountId: string, enabled: boolean) {
    const account = state.accounts.find(item => item.accountId === accountId)
    if (!account?.courierScope) {
      throw new ContractError('COURIER_SCOPE_MISSING', '该原型账号没有配送员记录，无法演示停用')
    }
    account.courierScope.status = enabled ? 2 : 3
    const admission = state.courierAdmissions.find(item => item.accountId === accountId)
    if (admission) {
      admission.status = enabled ? 2 : 3
    }
    const has = account.capabilities.includes('COURIER_WORK')
    if (enabled && !has) {
      account.capabilities.push('COURIER_WORK')
    }
    if (!enabled && has) {
      account.capabilities = account.capabilities.filter(item => item !== 'COURIER_WORK')
    }
  },
  reset() {
    Object.assign(state, cloneContractData(initialScenario))
  },
}
