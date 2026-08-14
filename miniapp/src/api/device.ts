import type {
  BusinessTime,
  EntityId,
  MoneyFen,
  PageQuery,
  PageResult,
  VolumeMl,
} from './common'
import type { OrderStatus, OrderType } from './order'
import { ContractError } from './common'
import { withRealSession } from './real-session'
import { post } from './request'

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
  /** 真实模式为运营档案值；1正常 2未激活 3欠费 4停用，未配置为 undefined。 */
  simStatus?: number
  simExpireTime?: BusinessTime
  outlets: OutletSummary[]
}

export interface ScanSession {
  scanSessionId: string
  deviceNo: string
  outletId: EntityId
  expiresAt: BusinessTime
}

/**
 * 卡/权益侧阻断原因，与设备可用性分组返回：两组均通过才允许下单。
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
  /**
   * 出水口摘要。S2 起 waterTypeId 与 unitPriceFenPerLiter 是扫码时点的<b>冻结报价</b>，
   * 不是当前档案值——服务端在报价漂移时直接返回 SCAN_QUOTE_CHANGED，不会给出混合体。
   */
  outlet: OutletSummary
  /** 报价生成时间（S2）。 */
  quotedAt?: BusinessTime
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
  /** 本期出水中由水卡水量支付的部分（毫升）：水费在充值环节已结算，对 orderAmountFen 贡献恒 0，单列防误读成漏记。 */
  prepaidVolumeMl: VolumeMl
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

/**
 * 交易快照周期必须复用服务端概览返回的同一时钟窗口：客户端按本地时区自算"近 7 日"会与服务端
 * Asia/Shanghai 自然日边界漂移，只传起点还会把未来时间的异常订单纳入。
 */
export function ownerTransactionPeriodQuery(
  period: string,
  overview?: Pick<OwnerOverview, 'periodStart' | 'periodEnd'>,
): Pick<OwnerTransactionQuery, 'periodStart' | 'periodEnd'> {
  if (period !== '7d') {
    return {}
  }
  if (!overview?.periodStart || !overview.periodEnd) {
    throw new ContractError('OWNER_PERIOD_MISSING', '经营统计周期缺失，请刷新后重试')
  }
  return {
    periodStart: overview.periodStart,
    periodEnd: overview.periodEnd,
  }
}

export type OwnerServiceType = 'REPAIR' | 'PART'
export type OwnerServiceStatus = 'PENDING_ACCEPTANCE' | 'PROCESSING' | 'COMPLETED' | 'REJECTED'

/** 处理轨迹条目（real 模式来自领域事件；操作端只区分 平台/机主/系统）。 */
export interface OwnerServiceTraceItem {
  eventTime: BusinessTime
  actorLabel: string
  detail: string
}

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
  /** 三端贯穿的真实工单号 */
  workOrderNo?: string
  /** 驳回原因（已驳回时返回） */
  rejectReason?: string
  /** 处理结果（完成后返回） */
  finishResult?: string
  /** 处理轨迹（仅详情返回） */
  trace?: OwnerServiceTraceItem[]
}

export interface CreateOwnerServiceInput {
  /** 申报幂等键：页面在一次提交意图内持有并于重试间复用（real 模式必填，mock 忽略） */
  requestId?: string
  deviceNo: string
  serviceType: OwnerServiceType
  description: string
  evidenceRefs: string[]
  contactPhone: string
}

export interface OwnerWalletFlow {
  flowType: number
  amountFen: number
  afterFen: number
  orderNo?: string
  createTime?: BusinessTime
}

/** 机主收益钱包（E2E-08 / REQ-072）：分润净额账务口径，与经营毛额（OwnerOverview）并存不互改。 */
export interface OwnerWallet {
  balanceFen: number
  frozenFen: number
  /** 冲减待补差额(分)（D-420）：>0 时提现暂不可用，后续分润入账优先补足；0=无差额 */
  clawbackDeficitFen: number
  /** D-421 在途分润(分)：已产生、尚在冻结期未入账的分账合计；0=无在途。 */
  pendingSplitFen: number
  /** 最早一笔在途分润的预计解冻时间；无在途时缺省。 */
  earliestUnfreezeTime?: BusinessTime
  flows: OwnerWalletFlow[]
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
  getOwnerServiceDetail: (requestId: string) => Promise<OwnerServiceRequest>
  createOwnerServiceRequest: (
    input: CreateOwnerServiceInput,
  ) => Promise<OwnerServiceRequest>
  getOwnerWallet: () => Promise<OwnerWallet>
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
  ownerServiceDetail: '/mini/owner/service/detail',
  ownerServiceCreate: '/mini/owner/service/create',
  ownerWallet: '/mini/owner/wallet',
} as const

/** 机主经营后端原始返回（Long→字符串序列化：金额/水量/total 为数值字符串）。 */
interface OwnerOverviewRaw {
  periodStart: string
  periodEnd: string
  stationCount?: number
  deviceCount?: number
  onlineCount?: number
  offlineCount?: number
  faultCount?: number
  orderCount?: number
  actualVolumeMl?: string | number | null
  orderAmountFen?: string | number | null
  prepaidVolumeMl?: string | number | null
}

interface OwnerTransactionRaw {
  orderNo: string
  orderType: number
  orderStatus: number
  stationId?: string | number | null
  stationName?: string | null
  deviceNo?: string | null
  actualVolumeMl?: string | number | null
  orderAmountFen?: string | number | null
  createTime: string
}

interface WaterEligibilityRaw {
  availability: DeviceAvailability
  reason?: string | null
  maxAllowedMl?: string | number | null
  remainingDailyLimitMl?: string | number | null
  cardBlock?: { code: CardBlockCode, message: string } | null
}

/** 数值归一化（与 order.ts 同口径）：只判 Number() 会放过 '1.5'、'1e3' 畸形值；非法值一律按缺省处理。 */
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
 * device 域真实适配器（取水 E2E-01、机主设备/报修 E2E-05、机主经营 E2E-06）：
 * 经营为订单口径毛额，数据范围由服务端按会话 OWNER_USER_ID 过滤，前端不传 userId。
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
      return {
        availability: raw.availability,
        reason: raw.reason ?? undefined,
        maxAllowedMl: strictVolume(raw.maxAllowedMl),
        remainingDailyLimitMl: strictVolume(raw.remainingDailyLimitMl),
        cardBlock: raw.cardBlock ?? undefined,
      }
    })
  },
  async listOwnerDevices() {
    return withRealSession(() => post<DeviceSummary[]>(deviceEndpoints.ownerList, {}))
  },
  async getOwnerDeviceDetail(deviceNo) {
    return withRealSession(() => post<DeviceDetail>(deviceEndpoints.ownerDetail, { deviceNo }))
  },
  async getOwnerOverview() {
    return withRealSession(async () => {
      const raw = await post<OwnerOverviewRaw>(deviceEndpoints.ownerOverview, {})
      return {
        periodStart: String(raw.periodStart),
        periodEnd: String(raw.periodEnd),
        stationCount: raw.stationCount ?? 0,
        deviceCount: raw.deviceCount ?? 0,
        onlineCount: raw.onlineCount ?? 0,
        offlineCount: raw.offlineCount ?? 0,
        faultCount: raw.faultCount ?? 0,
        orderCount: raw.orderCount ?? 0,
        actualVolumeMl: strictVolume(raw.actualVolumeMl) ?? 0,
        orderAmountFen: strictVolume(raw.orderAmountFen) ?? 0,
        prepaidVolumeMl: strictVolume(raw.prepaidVolumeMl) ?? 0,
      }
    })
  },
  async listOwnerTransactions(query = {}) {
    return withRealSession(async () => {
      const raw = await post<{ total?: string | number, list?: OwnerTransactionRaw[] }>(
        deviceEndpoints.ownerTransactions,
        {
          periodStart: query.periodStart,
          periodEnd: query.periodEnd,
          deviceNo: query.deviceNo,
          current: query.current,
          size: query.size,
        },
      )
      const list = (raw.list ?? []).map<OwnerTransactionItem>(item => ({
        orderNo: String(item.orderNo),
        orderType: item.orderType as OrderType,
        orderStatus: item.orderStatus as OrderStatus,
        stationId: String(item.stationId ?? ''),
        stationName: item.stationName ?? '未命名水站',
        deviceNo: item.deviceNo ?? undefined,
        actualVolumeMl: strictVolume(item.actualVolumeMl),
        orderAmountFen: strictVolume(item.orderAmountFen) ?? 0,
        createTime: String(item.createTime),
      }))
      return { list, total: strictVolume(raw.total) ?? list.length }
    })
  },
  async listOwnerServiceRequests() {
    return withRealSession(() => post<OwnerServiceRequest[]>(deviceEndpoints.ownerServiceList, {}))
  },
  async getOwnerServiceDetail(requestId) {
    return withRealSession(() => post<OwnerServiceRequest>(deviceEndpoints.ownerServiceDetail, { requestId }))
  },
  async createOwnerServiceRequest(input) {
    if (!input.requestId) {
      // 幂等键由页面在提交意图内持有；缺失说明调用方式错误，拒绝而不是替它生成（重试会翻倍建单）
      throw new ContractError('SERVICE_REQUEST_ID_REQUIRED', '申报提交异常，请重试')
    }
    return withRealSession(() => post<OwnerServiceRequest>(deviceEndpoints.ownerServiceCreate, {
      requestId: input.requestId,
      deviceNo: input.deviceNo,
      serviceType: input.serviceType,
      description: input.description,
      evidenceRefs: input.evidenceRefs,
      contactPhone: input.contactPhone,
    }))
  },

  async getOwnerWallet() {
    return withRealSession(async () => {
      const raw = await post<{
        balanceFen: unknown
        frozenFen: unknown
        clawbackDeficitFen?: unknown
        pendingSplitFen?: unknown
        earliestUnfreezeTime?: unknown
        flows?: Array<Record<string, unknown>>
      }>(
        deviceEndpoints.ownerWallet,
        {},
      )
      // R1 复验 P2：只认安全整数或规范十进制整数字符串，小数/科学计数法/超界/缺省一律收敛 0
      const toSafeInt = (value: unknown): number => {
        if (typeof value === 'number') {
          return Number.isSafeInteger(value) ? value : 0
        }
        if (typeof value === 'string' && /^-?\d+$/.test(value)) {
          const parsed = Number(value)
          return Number.isSafeInteger(parsed) ? parsed : 0
        }
        return 0
      }
      // 余额语义恒非负，负值只能是脏数据；流水金额不走本函数（提现冻结流水合法为负）
      const toNonNegativeFen = (value: unknown): number => {
        const fen = toSafeInt(value)
        return fen >= 0 ? fen : 0
      }
      return {
        balanceFen: toNonNegativeFen(raw.balanceFen),
        frozenFen: toNonNegativeFen(raw.frozenFen),
        clawbackDeficitFen: toNonNegativeFen(raw.clawbackDeficitFen),
        pendingSplitFen: toNonNegativeFen(raw.pendingSplitFen),
        earliestUnfreezeTime: typeof raw.earliestUnfreezeTime === 'string'
          ? raw.earliestUnfreezeTime as BusinessTime
          : undefined,
        flows: (raw.flows ?? []).map(row => ({
          flowType: toNonNegativeFen(row.flowType),
          amountFen: toSafeInt(row.amountFen),
          afterFen: toSafeInt(row.afterFen),
          orderNo: typeof row.orderNo === 'string' ? row.orderNo : undefined,
          createTime: typeof row.createTime === 'string' ? row.createTime as BusinessTime : undefined,
        })),
      }
    })
  },
}

export const deviceApi = realDeviceApi
