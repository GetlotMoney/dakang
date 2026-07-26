import type {
  BusinessTime,
  EntityId,
  MoneyFen,
  PageQuery,
  PageResult,
  VolumeMl,
} from './common'
import type { OrderStatus, OrderType } from './order'
import type { AccountContext, CapabilityCode } from './account'
import { requireCapability } from './capability'
import { cloneContractData, ContractError } from './common'
import { withRealSession } from './real-session'
import { post } from './request'
import { selectAdapter } from './runtime'
import { scenarioStore } from '@/scenario/store'

export type DeviceOnlineStatus = 'ONLINE' | 'OFFLINE'
export type DeviceRunStatus = 'IDLE' | 'DISPENSING' | 'FAULT' | 'MAINTENANCE' | 'LOCKED'
export type DeviceAvailability
  = | 'AVAILABLE'
    | 'DEVICE_OFFLINE'
    | 'FAULT_E001'
    | 'FAULT_E003'
    | 'FAULT_E004'
    | 'DEVICE_UNAVAILABLE'
    | 'NO_AVAILABLE_OUTLET'

export interface OutletSummary {
  outletId: EntityId
  outletNo: number
  waterTypeId: EntityId
  waterTypeName: string
  unitPriceFenPerLiter: number
  available: boolean
}

export interface DeviceSummary {
  deviceNo: string
  deviceName: string
  stationId: EntityId
  stationName: string
  onlineStatus: DeviceOnlineStatus
  runStatus: DeviceRunStatus
  lastHeartbeat?: BusinessTime
  lastFaultCode?: string
}

export interface DeviceDetail extends DeviceSummary {
  tds?: number
  temperatureCelsius?: number
  filterPercent?: number
  signalDbm?: number
  reportTime?: BusinessTime
  outlets: OutletSummary[]
}

export interface ScanSession {
  scanSessionId: string
  deviceNo: string
  outletId: EntityId
  expiresAt: BusinessTime
}

/**
 * 卡/权益侧阻断原因，与设备可用性分组返回（蓝图 §9.4：两组均通过才允许下单）。
 * CARD-SCOPE：预检指定 cardId，他人卡与不存在卡统一 CARD_NOT_ACCESSIBLE（不泄露存在性）；
 * 范围非法/未配置为 CARD_SCOPE_INVALID，范围未命中当前设备为 CARD_SCOPE_DENIED。
 */
export type CardBlockCode
  = | 'CARD_MISSING'
    | 'CARD_NOT_ACCESSIBLE'
    | 'CARD_SCOPE_INVALID'
    | 'CARD_SCOPE_DENIED'
    | 'CARD_FROZEN'
    | 'CARD_EXPIRED'
    | 'CARD_CANCELLED'

export interface WaterEligibility {
  availability: DeviceAvailability
  reason?: string
  /** 水量支付参考上限（=指定卡剩余水量）；只用于水量支付比对，余额支付不受其阻断（CARD-SCOPE）。 */
  maxAllowedMl?: VolumeMl
  /** 成员日剩余额度；仅成员取水时返回，不得与水量余量混用。 */
  remainingDailyLimitMl?: VolumeMl
  cardBlock?: { code: CardBlockCode, message: string }
}

export interface WaterDeviceContext {
  scanSessionId: string
  stationId: EntityId
  stationName: string
  deviceNo: string
  deviceName: string
  onlineStatus: DeviceOnlineStatus
  runStatus: DeviceRunStatus
  outlet: OutletSummary
  expiresAt: BusinessTime
}

export interface OwnerOverview {
  periodStart: BusinessTime
  periodEnd: BusinessTime
  stationCount: number
  deviceCount: number
  onlineCount: number
  offlineCount: number
  faultCount: number
  orderCount: number
  actualVolumeMl: VolumeMl
  orderAmountFen: MoneyFen
  evidenceMode: 'prototype' | 'external-snapshot'
}

export interface OwnerTransactionItem {
  orderNo: string
  orderType: OrderType
  orderStatus: OrderStatus
  stationId: EntityId
  stationName: string
  deviceNo?: string
  actualVolumeMl?: VolumeMl
  orderAmountFen: MoneyFen
  createTime: BusinessTime
}

export interface OwnerTransactionQuery {
  periodStart?: BusinessTime
  periodEnd?: BusinessTime
  deviceNo?: string
  current?: PageQuery['current']
  size?: PageQuery['size']
}

export type OwnerServiceType = 'REPAIR' | 'PART'
export type OwnerServiceStatus = 'PENDING_ACCEPTANCE' | 'PROCESSING' | 'COMPLETED' | 'REJECTED'

export interface OwnerServiceRequest {
  requestId: EntityId
  accountId: EntityId
  deviceNo: string
  serviceType: OwnerServiceType
  description: string
  evidenceRefs: string[]
  maskedContactPhone: string
  status: OwnerServiceStatus
  createTime: BusinessTime
  evidenceMode: 'prototype' | 'external-snapshot'
}

export interface CreateOwnerServiceInput {
  deviceNo: string
  serviceType: OwnerServiceType
  description: string
  evidenceRefs: string[]
  contactPhone: string
}

export interface DeviceApi {
  resolveScanCode: (rawCode: string) => Promise<ScanSession>
  getWaterDeviceContext: (scanSessionId: string) => Promise<WaterDeviceContext>
  /** CARD-SCOPE：预检必须指明 cardId（与下单同一张卡）；不传按未持卡返回 CARD_MISSING。 */
  checkWaterEligibility: (scanSessionId: string, cardId?: EntityId) => Promise<WaterEligibility>
  listOwnerDevices: () => Promise<DeviceSummary[]>
  getOwnerDeviceDetail: (deviceNo: string) => Promise<DeviceDetail>
  getOwnerOverview: () => Promise<OwnerOverview>
  listOwnerTransactions: (
    query?: OwnerTransactionQuery,
  ) => Promise<PageResult<OwnerTransactionItem>>
  listOwnerServiceRequests: () => Promise<OwnerServiceRequest[]>
  createOwnerServiceRequest: (
    input: CreateOwnerServiceInput,
  ) => Promise<OwnerServiceRequest>
}

export const deviceEndpoints = {
  resolveScan: '/mini/device/scan/resolve',
  waterContext: '/mini/device/water/context',
  eligibility: '/mini/device/water/eligibility',
  ownerList: '/mini/owner/device/list',
  ownerDetail: '/mini/owner/device/detail',
  ownerOverview: '/mini/owner/overview',
  ownerTransactions: '/mini/owner/transaction/page',
  ownerServiceList: '/mini/owner/service/list',
  ownerServiceCreate: '/mini/owner/service/create',
} as const

function ownerContext(capability: CapabilityCode): AccountContext {
  const context = scenarioStore.activeAccount()
  requireCapability(context, capability)
  const scope = context.ownerScope
  if (!scope || scope.stationIds.length === 0 || scope.deviceNos.length === 0) {
    throw new ContractError('OWNER_SCOPE_DENIED', '机主授权范围为空，默认不可访问经营数据')
  }
  return context
}

function ownerDevice(deviceNo: string) {
  const context = ownerContext('OWNER_VIEW')
  if (!context.ownerScope!.deviceNos.includes(deviceNo)) {
    throw new ContractError('DEVICE_ACCESS_DENIED', '设备不在当前机主授权范围')
  }
  const device = scenarioStore.devices.find(item => item.deviceNo === deviceNo)
  if (!device) {
    throw new ContractError('DEVICE_NOT_FOUND', '设备不存在')
  }
  return device
}

function maskPhone(phone: string) {
  if (!/^1\d{10}$/.test(phone)) {
    throw new ContractError('PHONE_INVALID', '手机号格式不合法')
  }
  return `${phone.slice(0, 3)}****${phone.slice(-4)}`
}

/**
 * 扫码码值对齐 PC 事实源 `ws_qrcode.QRCODE_CONTENT`（2026-07-16 对表）：
 * DK-QR-DEV0001-O1/O2 为种子真实码；DK-QR-DEV0002-O1 为演示扩展码（PC 种子暂缺设备 2 的码，已上报补种子建议）。
 * 原始二维码只在适配器内解析为短期会话，不进入路由或日志。
 */
const SCAN_SAMPLE_CODES: Record<string, { deviceNo: string, outletId: EntityId }> = {
  'DK-QR-DEV0001-O1': { deviceNo: 'DK-DEV-0001', outletId: '1' },
  'DK-QR-DEV0001-O2': { deviceNo: 'DK-DEV-0001', outletId: '2' },
  'DK-QR-DEV0002-O1': { deviceNo: 'DK-DEV-0002', outletId: '3' },
}

function requireActiveScanSession(scanSessionId: string): ScanSession {
  const session = scenarioStore.findActiveScanSession(scanSessionId)
  if (!session) {
    throw new ContractError('SCAN_SESSION_EXPIRED', '扫码会话已失效，请重新扫码')
  }
  return session
}

function deviceBySession(session: ScanSession) {
  const device = scenarioStore.devices.find(item => item.deviceNo === session.deviceNo)
  if (!device) {
    throw new ContractError('DEVICE_NOT_FOUND', '设备不存在')
  }
  return device
}

/** 按设备在线/运行状态派生可用性，故障码映射到统一枚举。 */
function deriveDeviceAvailability(device: DeviceDetail, outletId: EntityId): {
  availability: DeviceAvailability
  reason?: string
} {
  if (device.onlineStatus === 'OFFLINE') {
    return { availability: 'DEVICE_OFFLINE', reason: '设备离线（最后心跳超过 90 秒），已阻断下单' }
  }
  if (device.runStatus === 'FAULT') {
    const faultCode = device.lastFaultCode ?? ''
    const known: Record<string, DeviceAvailability> = {
      E001: 'FAULT_E001',
      E003: 'FAULT_E003',
      E004: 'FAULT_E004',
    }
    return {
      availability: known[faultCode] ?? 'DEVICE_UNAVAILABLE',
      reason: `设备故障${faultCode ? `（${faultCode}）` : ''}，已阻断下单`,
    }
  }
  if (device.runStatus === 'MAINTENANCE' || device.runStatus === 'LOCKED') {
    return {
      availability: 'DEVICE_UNAVAILABLE',
      reason: device.runStatus === 'MAINTENANCE' ? '设备维护中，暂不可取水' : '设备已锁定，暂不可取水',
    }
  }
  const outlet = device.outlets.find(item => item.outletId === outletId)
  if (!outlet || !outlet.available) {
    return { availability: 'NO_AVAILABLE_OUTLET', reason: '出水口不可用，请更换出水口' }
  }
  return { availability: 'AVAILABLE' }
}

/**
 * 指定卡状态映射为卡侧阻断原因；正常卡返回 undefined。
 * CARD-SCOPE：传入 cardId 时只检查该卡（与后端「预检卡=下单卡」口径一致）；
 * 他人卡与不存在卡统一 CARD_NOT_ACCESSIBLE，不泄露存在性。
 * 未传 cardId 时沿用主卡回退，保持既有 Mock 场景可用。
 */
function deriveCardBlock(cardId?: EntityId): WaterEligibility['cardBlock'] {
  const userId = scenarioStore.activeAccount().userId
  const card = cardId
    ? scenarioStore.cards.find(item => item.cardId === cardId)
    : scenarioStore.cards.find(item => item.userId === userId)
  if (!card) {
    return cardId
      ? { code: 'CARD_NOT_ACCESSIBLE', message: '水卡不存在或无权使用' }
      : { code: 'CARD_MISSING', message: '当前账号没有可用水卡，请先购卡或充值' }
  }
  if (cardId && card.userId !== userId) {
    // 与「不存在」同文案同码：可区分即成为探测他人 cardId 的信道
    return { code: 'CARD_NOT_ACCESSIBLE', message: '水卡不存在或无权使用' }
  }
  const blocked: Partial<Record<number, WaterEligibility['cardBlock']>> = {
    2: { code: 'CARD_FROZEN', message: '水卡已冻结，请联系客服处理后再取水' },
    3: { code: 'CARD_EXPIRED', message: '水卡已过期，请续费后再取水' },
    4: { code: 'CARD_CANCELLED', message: '水卡已注销，无法继续使用' },
  }
  return blocked[card.cardStatus]
}

const mockDeviceApi: DeviceApi = {
  async resolveScanCode(rawCode) {
    if (rawCode === 'DK-QR-EXPIRED') {
      throw new ContractError('QR_EXPIRED', '二维码已过期或已停用，请按设备屏幕提示重新获取')
    }
    // 万能码（ws_qrcode type=3 种子 DK-QR-UNIVERSAL-001）：扫后选设备/出水口的流程待定型，先给明确契约状态。
    if (rawCode === 'DK-QR-UNIVERSAL-001') {
      throw new ContractError('UNIVERSAL_CODE_PENDING', '万能码需现场选择设备与出水口，该流程待后续定型')
    }
    const target = SCAN_SAMPLE_CODES[rawCode]
    if (!target) {
      throw new ContractError('INVALID_QR_CODE', '二维码无效，请扫描设备出水口上的取水码')
    }
    return cloneContractData(scenarioStore.registerScanSession(target.deviceNo, target.outletId))
  },
  async getWaterDeviceContext(scanSessionId) {
    const session = requireActiveScanSession(scanSessionId)
    const device = deviceBySession(session)
    const outlet = device.outlets.find(item => item.outletId === session.outletId)
    if (!outlet) {
      throw new ContractError('OUTLET_NOT_FOUND', '出水口不存在')
    }
    return cloneContractData({
      scanSessionId,
      stationId: device.stationId,
      stationName: device.stationName,
      deviceNo: device.deviceNo,
      deviceName: device.deviceName,
      onlineStatus: device.onlineStatus,
      runStatus: device.runStatus,
      outlet,
      expiresAt: session.expiresAt,
    })
  },
  async checkWaterEligibility(scanSessionId, cardId) {
    const session = requireActiveScanSession(scanSessionId)
    const device = deviceBySession(session)
    const derived = deriveDeviceAvailability(device, session.outletId)
    return cloneContractData({
      availability: derived.availability,
      reason: derived.reason,
      maxAllowedMl: scenarioStore.waterEligibility.maxAllowedMl,
      cardBlock: deriveCardBlock(cardId),
    })
  },
  async listOwnerDevices() {
    const context = ownerContext('OWNER_VIEW')
    const deviceNos = context.ownerScope!.deviceNos
    return cloneContractData(
      scenarioStore.devices.filter(item => deviceNos.includes(item.deviceNo)),
    )
  },
  async getOwnerDeviceDetail(deviceNo) {
    return cloneContractData(ownerDevice(deviceNo))
  },
  async getOwnerOverview() {
    const context = ownerContext('OWNER_VIEW')
    const scope = context.ownerScope!
    const devices = scenarioStore.devices.filter(item => scope.deviceNos.includes(item.deviceNo))
    // 统计口径与 periodStart 标注保持一致：只聚合周期内订单。
    const periodStart = '20260712000000'
    const orders = scenarioStore.orderDetails
      .map(item => item.order)
      .filter(item => item.stationId && scope.stationIds.includes(item.stationId))
      .filter(item => item.createTime >= periodStart)
    return cloneContractData({
      periodStart,
      periodEnd: scenarioStore.now,
      stationCount: scope.stationIds.length,
      deviceCount: devices.length,
      onlineCount: devices.filter(item => item.onlineStatus === 'ONLINE').length,
      offlineCount: devices.filter(item => item.onlineStatus === 'OFFLINE').length,
      faultCount: devices.filter(item => item.runStatus === 'FAULT').length,
      orderCount: orders.length,
      actualVolumeMl: orders.reduce((sum, item) => sum + (item.actualMl ?? 0), 0),
      orderAmountFen: orders.reduce((sum, item) => sum + item.orderAmountFen, 0),
      evidenceMode: 'prototype',
    })
  },
  async listOwnerTransactions(query = {}) {
    const context = ownerContext('OWNER_VIEW')
    const scope = context.ownerScope!
    if (query.deviceNo && !scope.deviceNos.includes(query.deviceNo)) {
      throw new ContractError('DEVICE_ACCESS_DENIED', '设备不在当前机主授权范围')
    }
    const current = query.current ?? 1
    const size = query.size ?? 20
    if (current <= 0 || size <= 0) {
      throw new ContractError('PAGE_QUERY_INVALID', '分页参数必须大于 0')
    }
    const matched = scenarioStore.orderDetails
      .map(item => item.order)
      .filter(item => item.stationId && scope.stationIds.includes(item.stationId))
      .filter(item => !query.deviceNo || item.deviceNo === query.deviceNo)
      .filter(item => !query.periodStart || item.createTime >= query.periodStart)
      .filter(item => !query.periodEnd || item.createTime <= query.periodEnd)
      .map<OwnerTransactionItem>(item => ({
        orderNo: item.orderNo,
        orderType: item.orderType,
        orderStatus: item.orderStatus,
        stationId: item.stationId!,
        stationName: item.stationName ?? '未命名水站',
        deviceNo: item.deviceNo,
        actualVolumeMl: item.actualMl,
        orderAmountFen: item.orderAmountFen,
        createTime: item.createTime,
      }))
    const start = (current - 1) * size
    return cloneContractData({
      list: matched.slice(start, start + size),
      total: matched.length,
    })
  },
  async listOwnerServiceRequests() {
    const context = ownerContext('OWNER_SERVICE')
    return cloneContractData(
      scenarioStore.ownerServiceRequests.filter(item => item.accountId === context.accountId),
    )
  },
  async createOwnerServiceRequest(input) {
    const context = ownerContext('OWNER_SERVICE')
    if (!context.ownerScope!.deviceNos.includes(input.deviceNo)) {
      throw new ContractError('DEVICE_ACCESS_DENIED', '设备不在当前机主授权范围')
    }
    if (!input.description.trim()) {
      throw new ContractError('SERVICE_DESCRIPTION_REQUIRED', '问题说明不能为空')
    }
    // 动作发生时间由统一逻辑时钟取得，记录与审计同源（第五轮审计整改）。
    const createTime = scenarioStore.takeBusinessTime()
    const request: OwnerServiceRequest = {
      requestId: `OSR-${scenarioStore.ownerServiceRequests.length + 1}`,
      accountId: context.accountId,
      deviceNo: input.deviceNo,
      serviceType: input.serviceType,
      description: input.description.trim(),
      evidenceRefs: [...input.evidenceRefs],
      maskedContactPhone: maskPhone(input.contactPhone),
      status: 'PENDING_ACCEPTANCE',
      createTime,
      evidenceMode: 'prototype',
    }
    scenarioStore.ownerServiceRequests.push(request)
    scenarioStore.recordAudit(
      'OWNER_SERVICE',
      'owner.service.create',
      'service-request',
      request.requestId,
      'success',
      createTime,
    )
    return cloneContractData(request)
  },
}

/**
 * eligibility 后端原始返回：后端全局 Jackson 将 Long 序列化为字符串（防 JS 精度丢失），
 * 故 maxAllowedMl/remainingDailyLimitMl 到达前端是数值字符串；其余字段与前端类型一致。
 */
interface WaterEligibilityRaw {
  availability: DeviceAvailability
  reason?: string | null
  maxAllowedMl?: string | number | null
  remainingDailyLimitMl?: string | number | null
  cardBlock?: { code: CardBlockCode, message: string } | null
}

/**
 * 数值字段归一化（strictNumber 手法，与 order.ts 同口径）：水量是毫升整数，
 * 只判 Number() 会把 '1.5'、'1e3' 这类畸形值一路放进比较逻辑；非法值一律按缺省处理。
 */
function strictVolume(value: string | number | null | undefined): number | undefined {
  if (value == null) {
    return undefined
  }
  if (typeof value === 'number') {
    return Number.isSafeInteger(value) && value >= 0 ? value : undefined
  }
  if (/^(?:0|[1-9]\d*)$/.test(value)) {
    const n = Number(value)
    return Number.isSafeInteger(n) ? n : undefined
  }
  return undefined
}

/**
 * device 域真实适配器（L1a 扫码取水链）。
 *
 * 仅 scan/resolve、water/context、water/eligibility 三接口接真（后端 /mini/device/* 已就绪）；
 * owner 系列后端 /mini/owner/* 尚未建，暂委托 mock 保持机主经营页面在 device 域接真后仍可用，
 * 待 owner 接真切片再逐个替换（不引入 realAdapterPending 以免打断已封板演示）。
 */
const realDeviceApi: DeviceApi = {
  async resolveScanCode(rawCode) {
    // 扫码原文只在此换取短期会话，不进入路由或日志（对齐 mock 适配器口径）。
    return withRealSession(() =>
      post<ScanSession>(deviceEndpoints.resolveScan, { rawCode }),
    )
  },
  async getWaterDeviceContext(scanSessionId) {
    return withRealSession(() =>
      post<WaterDeviceContext>(deviceEndpoints.waterContext, { scanSessionId }),
    )
  },
  async checkWaterEligibility(scanSessionId, cardId) {
    return withRealSession(async () => {
      // CARD-SCOPE：cardId 随请求上送，后端只预检这张卡（预检卡=下单卡）
      const raw = await post<WaterEligibilityRaw>(deviceEndpoints.eligibility, { scanSessionId, cardId })
      // 数值字符串按 strictNumber 手法归一化；后端 null/畸形值归一化为 undefined。
      return {
        availability: raw.availability,
        reason: raw.reason ?? undefined,
        maxAllowedMl: strictVolume(raw.maxAllowedMl),
        remainingDailyLimitMl: strictVolume(raw.remainingDailyLimitMl),
        cardBlock: raw.cardBlock ?? undefined,
      }
    })
  },
  // owner 端点尚未接入真实接口：继续使用 Mock，避免 device 域接真后影响机主页面。
  listOwnerDevices: () => mockDeviceApi.listOwnerDevices(),
  getOwnerDeviceDetail: deviceNo => mockDeviceApi.getOwnerDeviceDetail(deviceNo),
  getOwnerOverview: () => mockDeviceApi.getOwnerOverview(),
  listOwnerTransactions: query => mockDeviceApi.listOwnerTransactions(query),
  listOwnerServiceRequests: () => mockDeviceApi.listOwnerServiceRequests(),
  createOwnerServiceRequest: input => mockDeviceApi.createOwnerServiceRequest(input),
}

export const deviceApi = selectAdapter(mockDeviceApi, realDeviceApi, 'device')
