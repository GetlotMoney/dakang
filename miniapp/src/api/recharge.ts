import type { EntityId, MoneyFen, VolumeMl } from './common'
import type { CardSummary } from './card'
import type { OrderStatus } from './order'
import { ContractError } from './common'
import { withRealSession } from './real-session'
import { post } from './request'

export type PayStatus = 1 | 2 | 3 | 4

export interface RechargePackage {
  id: EntityId
  packageName: string
  payAmountFen: MoneyFen
  waterMl: VolumeMl
  bonusAmountFen: MoneyFen
  unitPriceSnap: string
  /** 套餐有效期天数；null 表示永久。 */
  expireDays: number | null
  packageStatus: 1 | 2
  /** 服务端严格校验套餐范围后的可购结论；前端不得自行解析范围。 */
  purchasable: boolean
}

export interface CreateRechargeOrderInput {
  /** 缺省表示首次购卡；已有卡充值必须传本人 cardId。 */
  cardId?: EntityId
  packageId: EntityId
  requestId: string
}

export type RechargePageMode = 'purchase' | 'recharge'

/** 是否已有目标卡是 U10 两种业务态的唯一分流条件，不提供人工切换入口。 */
export function resolveRechargePageMode(card: CardSummary | null): RechargePageMode {
  return card ? 'recharge' : 'purchase'
}

/** 首次购卡只展示服务端明确判为可购的套餐；空结果禁止回落为全量套餐。 */
export function visiblePackagesForMode(
  packages: RechargePackage[],
  mode: RechargePageMode,
): RechargePackage[] {
  return mode === 'purchase' ? packages.filter(item => item.purchasable === true) : [...packages]
}

export interface RechargeOrderResult {
  orderNo: string
  payAmountFen: MoneyFen
  payStatus: PayStatus
  /** 订单状态(1341)，创单后固定 1 待支付。 */
  orderStatus?: OrderStatus
  /** 不可变付款截止时间 yyyyMMddHHmmss；超过即不可再付。 */
  payExpireTime?: string
  /** 下单时的套餐名快照。 */
  packageName?: string
  /** true=命中既有订单（同 requestId 重复提交），未新建。 */
  idempotentHit?: boolean
  /** 以下三项仅 Mock 原型与将来真实微信支付使用；Pay-Sim 链路不产生。 */
  orderId?: EntityId
  prepayId?: string
  payParams?: Record<string, string>
}

export interface RechargePayStatus {
  orderNo: string
  payStatus: PayStatus
  orderStatus: OrderStatus
  /** 结构化状态码；MISMATCH 表示服务端判定数据不一致，前端一律停止轮询。 */
  payStatusCode?: string
  statusMessage?: string
  /** 仅当服务端明确允许时才继续轮询——不可由前端自行决定重试。 */
  retryable?: boolean
  processingStatus?: string
  payExpireTime?: string
  finishTime?: string
}

/** 模拟支付结果（仅测试环境的 Pay-Sim 链路）。 */
export interface RechargePaySimResult {
  orderNo: string
  resultCode: string
  message: string
}

export interface RechargeApi {
  listPackages: () => Promise<RechargePackage[]>
  createRechargeOrder: (input: CreateRechargeOrderInput) => Promise<RechargeOrderResult>
  getPayStatus: (orderNo: string) => Promise<RechargePayStatus>
  /** 触发一次模拟支付；真实微信支付接入后这里换成 requestPayment。 */
  simulatePay: (orderNo: string) => Promise<RechargePaySimResult>
}

export const rechargeEndpoints = {
  packages: '/mini/package/list',
  create: '/mini/order/recharge/create',
  payStatus: '/mini/order/pay-status',
  paySim: '/mini/pay-sim/pay',
} as const

/** null 明确表示永久；字段缺失、非整数或非正数均拒绝，禁止把坏响应静默解释为永久套餐。 */
export function normalizeRechargeExpireDays(value: unknown): number | null {
  if (value === null) {
    return null
  }
  if (typeof value !== 'number' || !Number.isInteger(value) || value <= 0) {
    throw new ContractError('PACKAGE_EXPIRE_DAYS_INVALID', '套餐信息有误，暂时无法充值')
  }
  return value
}

/**
 * 幂等键：必须是规范小写 UUID（后端 `RechargeOrderNo.derive` 只接受规范 UUID 并据此派生订单号）；
 * 不用 `crypto.randomUUID()`——小程序运行时没有该 API。
 */
export function createRechargeRequestId(): string {
  return uuidV4()
}

function uuidV4(): string {
  const bytes = new Uint8Array(16)
  for (let i = 0; i < 16; i++) {
    bytes[i] = Math.floor(Math.random() * 256)
  }
  // 版本位 4 与变体位 10xx，保证是规范 v4，否则后端正则会拒
  bytes[6] = (bytes[6] & 0x0F) | 0x40
  bytes[8] = (bytes[8] & 0x3F) | 0x80
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

// ---------------------------------------------------------------------------
// 真实适配器（L2-T）
// ---------------------------------------------------------------------------

export interface PackageRaw {
  packageId?: unknown
  packageName?: unknown
  payAmountFen?: unknown
  waterMl?: unknown
  bonusAmountFen?: unknown
  unitPriceSnap?: unknown
  expireDays?: unknown
  purchasable?: unknown
}

/**
 * 数值字段解析：必须同时接受 number 与十进制字符串（后端 Long 序列化为字符串防精度丢失）；
 * 空串、小数、指数、前导零、非十进制一律拒绝，绝不 `Number()` 一把梭。
 */
function requireNumber(value: unknown, field: string): number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string' && /^(?:0|-?[1-9]\d*)$/.test(value)) {
    const parsed = Number(value)
    if (!Number.isSafeInteger(parsed)) {
      throw new ContractError(`RECHARGE_FIELD_INVALID:${field}`, '充值数据异常，请稍后重试')
    }
    return parsed
  }
  throw new ContractError(`RECHARGE_FIELD_INVALID:${field}`, '充值数据异常，请稍后重试')
}

/** ID 字段：保留字符串原样（不转 number，避免大 ID 精度丢失），只校验是正十进制。 */
function requireId(value: unknown, field: string): string {
  const raw = typeof value === 'number' ? String(value) : value
  if (typeof raw !== 'string' || !/^[1-9]\d*$/.test(raw)) {
    throw new ContractError(`RECHARGE_FIELD_INVALID:${field}`, '充值数据异常，请稍后重试')
  }
  return raw
}

function requireText(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new ContractError(`RECHARGE_FIELD_INVALID:${field}`, '充值数据异常，请稍后重试')
  }
  return value
}

function requireBoolean(value: unknown, field: string): boolean {
  if (typeof value !== 'boolean') {
    throw new ContractError(`RECHARGE_FIELD_INVALID:${field}`, '充值数据异常，请稍后重试')
  }
  return value
}

/** 套餐归一化：金额/水量缺失一律拒绝而不是补 0——补 0 会把 500 元套餐显示成 0 元。 */
export function normalizePackage(raw: PackageRaw): RechargePackage {
  return {
    id: requireId(raw.packageId, 'packageId'),
    packageName: requireText(raw.packageName, 'packageName'),
    payAmountFen: requireNumber(raw.payAmountFen, 'payAmountFen'),
    waterMl: requireNumber(raw.waterMl, 'waterMl'),
    // 必须走同一套解析：后端 Long 是字符串，「不是 number 就取 0」会把赠送额显示成 ¥0.00
    bonusAmountFen: requireNumber(raw.bonusAmountFen ?? 0, 'bonusAmountFen'),
    unitPriceSnap: requireText(raw.unitPriceSnap, 'unitPriceSnap'),
    expireDays: raw.expireDays == null ? null : normalizeRechargeExpireDays(raw.expireDays),
    packageStatus: 1,
    purchasable: requireBoolean(raw.purchasable, 'purchasable'),
  }
}

/**
 * 真实创单请求体：首次购卡必须彻底省略 cardId——传 null/空串/"undefined" 会把坏请求误分流成购卡，
 * 除真正的 undefined 外一律拒绝。
 */
export function buildRechargeCreateBody(input: CreateRechargeOrderInput): Record<string, string> {
  const body: Record<string, string> = {
    packageId: String(input.packageId),
    requestId: input.requestId,
  }
  if (input.cardId !== undefined) {
    const cardId = String(input.cardId)
    if (!/^[1-9]\d*$/.test(cardId)) {
      throw new ContractError('RECHARGE_CARD_ID_INVALID', '水卡信息有误，无法创建充值订单')
    }
    body.cardId = cardId
  }
  return body
}

/** 支付状态归一化：retryable 缺失取 false——默认可重试会在服务端已判 MISMATCH 的单子上无限轮询。 */
export function normalizePayStatus(raw: Record<string, unknown>): RechargePayStatus {
  return {
    orderNo: requireText(raw.orderNo, 'orderNo'),
    payStatus: requireNumber(raw.payStatus, 'payStatus') as PayStatus,
    orderStatus: requireNumber(raw.orderStatus, 'orderStatus') as OrderStatus,
    payStatusCode: typeof raw.payStatusCode === 'string' ? raw.payStatusCode : undefined,
    statusMessage: typeof raw.statusMessage === 'string' ? raw.statusMessage : undefined,
    retryable: raw.retryable === true,
    processingStatus: typeof raw.processingStatus === 'string' ? raw.processingStatus : undefined,
    payExpireTime: typeof raw.payExpireTime === 'string' ? raw.payExpireTime : undefined,
    finishTime: typeof raw.finishTime === 'string' ? raw.finishTime : undefined,
  }
}

const realRechargeApi: RechargeApi = {
  async listPackages() {
    return withRealSession(async () => {
      const rows = await post<PackageRaw[] | null>(rechargeEndpoints.packages, {})
      return (rows ?? []).map(normalizePackage)
    })
  },
  async createRechargeOrder(input) {
    return withRealSession(async () => {
      const raw = await post<Record<string, unknown>>(
        rechargeEndpoints.create,
        buildRechargeCreateBody(input),
      )
      return {
        orderNo: requireText(raw.orderNo, 'orderNo'),
        payAmountFen: requireNumber(raw.orderAmountFen, 'orderAmountFen'),
        payStatus: requireNumber(raw.payStatus, 'payStatus') as PayStatus,
        orderStatus: requireNumber(raw.orderStatus, 'orderStatus') as OrderStatus,
        payExpireTime: typeof raw.payExpireTime === 'string' ? raw.payExpireTime : undefined,
        packageName: typeof raw.packageName === 'string' ? raw.packageName : undefined,
        idempotentHit: raw.idempotentHit === true,
      }
    })
  },
  async getPayStatus(orderNo) {
    return withRealSession(async () => {
      const raw = await post<Record<string, unknown>>(rechargeEndpoints.payStatus, { orderNo })
      return normalizePayStatus(raw)
    })
  },
  async simulatePay(orderNo) {
    return withRealSession(async () => {
      const raw = await post<Record<string, unknown>>(rechargeEndpoints.paySim, { orderNo })
      return {
        orderNo: requireText(raw.orderNo, 'orderNo'),
        resultCode: requireText(raw.resultCode, 'resultCode'),
        message: typeof raw.message === 'string' ? raw.message : '',
      }
    })
  },
}

export const rechargeApi = realRechargeApi
