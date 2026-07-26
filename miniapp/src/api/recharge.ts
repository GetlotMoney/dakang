import type { EntityId, MoneyFen, VolumeMl } from './common'
import type { CardSummary } from './card'
import type { OrderDetail, OrderStatus } from './order'
import { cloneContractData, ContractError, prototypeMeta } from './common'
import { rechargeBlockFromLegacySnapshot } from './order'
import { withRealSession } from './real-session'
import { post } from './request'
import { currentMode, selectAdapter } from './runtime'
import { scenarioStore } from '@/scenario/store'

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

function mockUnitPriceSnap(payAmountFen: number, waterMl: number): string {
  return waterMl > 0 ? ((payAmountFen * 1000) / waterMl).toFixed(2) : '0.00'
}

function rechargeRequestId(detail: OrderDetail): string | undefined {
  if (!detail.order.packageSnapshot) {
    return undefined
  }
  try {
    const snapshot = JSON.parse(detail.order.packageSnapshot) as { requestId?: unknown }
    return typeof snapshot.requestId === 'string' ? snapshot.requestId : undefined
  }
  catch {
    return undefined
  }
}

function mockResult(detail: OrderDetail): RechargeOrderResult {
  return {
    orderId: detail.order.orderId,
    orderNo: detail.order.orderNo,
    payAmountFen: detail.order.orderAmountFen,
    payStatus: detail.order.orderStatus === 1 ? 1 : 2,
    prepayId: `PREPAY-${detail.order.orderNo}`,
    payParams: { mode: 'prototype' },
  }
}

const mockRechargeApi: RechargeApi = {
  async listPackages() {
    return cloneContractData(
      scenarioStore.packages.map(item => ({
        ...item,
        unitPriceSnap: mockUnitPriceSnap(item.payAmountFen, item.waterMl),
        packageStatus: 1 as const,
        // Mock 账号均已有卡；这里只补齐列表契约，不新增 Mock 首次购卡剧本。
        purchasable: true,
      })),
    )
  },
  async createRechargeOrder(input) {
    const requestId = input.requestId.trim()
    if (!requestId || requestId.length > 64) {
      throw new ContractError('RECHARGE_REQUEST_ID_INVALID', '充值请求编号不能为空且不能超过 64 个字符')
    }

    if (input.cardId === undefined) {
      throw new ContractError(
        'MOCK_PURCHASE_UNAVAILABLE',
        'Mock 域不提供首次购卡剧本，请在接真环境验证该业务链',
      )
    }

    const account = scenarioStore.activeAccount()
    const existing = scenarioStore.orderDetails.find(
      item =>
        item.order.userId === account.userId
        && item.order.orderType === 2
        && rechargeRequestId(item) === requestId,
    )
    if (existing) {
      // 幂等命中必须核对购买参数（2026-07-20 最终收口轮，对齐 L2 契约 §2 步 3）：
      // 同一 requestId 指向不同 card/package 属参数冲突，拒绝而非静默返回旧单。
      let existingPackageId: string | undefined
      try {
        const snapshot = JSON.parse(existing.order.packageSnapshot ?? '{}') as { packageId?: unknown }
        existingPackageId = snapshot.packageId == null ? undefined : String(snapshot.packageId)
      }
      catch {
        existingPackageId = undefined
      }
      if (existing.order.cardId !== input.cardId || existingPackageId !== input.packageId) {
        throw new ContractError(
          'RECHARGE_REQUEST_CONFLICT',
          '同一充值请求编号不能对应不同的水卡或套餐，请刷新后重新发起购买',
        )
      }
      return cloneContractData(mockResult(existing))
    }

    const card = scenarioStore.cards.find(
      item => item.cardId === input.cardId && item.userId === account.userId,
    )
    if (!card) {
      throw new ContractError('CARD_NOT_FOUND', '水卡不存在或无权使用')
    }
    if (card.cardStatus !== 1) {
      throw new ContractError('CARD_NOT_USABLE', '水卡状态不可用（冻结/过期/注销），请先联系客服处理')
    }

    const selectedPackage = scenarioStore.packages.find(item => item.id === input.packageId)
    if (!selectedPackage) {
      throw new ContractError('PACKAGE_NOT_FOUND', '套餐不存在或已下架')
    }

    const sequence = scenarioStore.nextOrderSequence()
    const orderNo = `MR20260716${sequence}`
    const createTime = scenarioStore.takeBusinessTime()
    const detail: OrderDetail = {
      order: {
        orderId: `MO-${sequence}`,
        orderNo,
        userId: account.userId,
        orderType: 2,
        orderStatus: 1,
        orderAmountFen: selectedPackage.payAmountFen,
        payWay: 1,
        cardId: card.cardId,
        packageSnapshot: JSON.stringify({
          requestId,
          packageId: selectedPackage.id,
          packageName: selectedPackage.packageName,
          payAmount: selectedPackage.payAmountFen,
          waterMl: selectedPackage.waterMl,
          bonusAmount: selectedPackage.bonusAmountFen,
          // 有效期写入订单快照；待支付原型单不修改水卡有效期。
          expireDays: selectedPackage.expireDays,
          unitPriceSnap: mockUnitPriceSnap(selectedPackage.payAmountFen, selectedPackage.waterMl),
        }),
        createTime,
        mockMeta: { ...prototypeMeta },
      },
      trace: [
        {
          node: 'pending-payment',
          label: '等待支付能力接入',
          time: createTime,
          tone: 'warning',
        },
      ],
      flowCount: 0,
    }
    scenarioStore.orderDetails.unshift(detail)
    scenarioStore.recordAudit(
      'USER_BASE',
      'order.recharge.create',
      'order',
      orderNo,
      'success',
      createTime,
    )
    return cloneContractData(mockResult(detail))
  },
  async getPayStatus(orderNo) {
    const account = scenarioStore.activeAccount()
    const detail = scenarioStore.orderDetails.find(
      item => item.order.orderNo === orderNo && item.order.userId === account.userId,
    )
    if (!detail || detail.order.orderType !== 2) {
      throw new ContractError('ORDER_NOT_FOUND', '充值订单不存在或无权访问')
    }
    const orderStatus = detail.order.orderStatus
    const payStatus: PayStatus = orderStatus === 1
      ? 1
      : orderStatus === 5
        ? 4
        : [2, 4, 7, 8].includes(orderStatus)
            ? 2
            : 3
    return cloneContractData({ orderNo, payStatus, orderStatus })
  },
  // Mock 原型没有支付能力，也不该假装有：明确拒绝，避免演示时把「点了就到账」当成真链路。
  async simulatePay(orderNo) {
    throw new ContractError(
      'PAY_SIM_UNAVAILABLE',
      `原型模式不提供支付能力（${orderNo}），请切换到充值域接真的构建`,
    )
  },
}

/** null 明确表示永久；字段缺失、非整数或非正数均拒绝，禁止把坏响应静默解释为永久套餐。 */
export function normalizeRechargeExpireDays(value: unknown): number | null {
  if (value === null) {
    return null
  }
  if (typeof value !== 'number' || !Number.isInteger(value) || value <= 0) {
    throw new ContractError('PACKAGE_EXPIRE_DAYS_INVALID', '套餐有效期缺失或格式错误，不能继续充值')
  }
  return value
}

/**
 * 幂等键。
 *
 * Mock 域用可预测序号（原型演示要能复现同一编号）；**接真时必须是规范小写 UUID**——
 * 后端 `RechargeOrderNo.derive` 只接受规范 UUID 并据此派生订单号，
 * 此前接真也发 `recharge-mock-0001`，创单在服务端一律被拒，充值根本走不通。
 *
 * 不用 `crypto.randomUUID()`：小程序运行时没有该 API。
 */
export function createRechargeRequestId(): string {
  if (currentMode('recharge') === 'mock') {
    const sequence = scenarioStore.nextRechargeRequestSequence()
    return `recharge-mock-${String(sequence).padStart(4, '0')}`
  }
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

export type OrderDetailDataSource = 'real' | 'local-mock'

export function findLocalRechargeOrder(orderNo: string): OrderDetail | null {
  const account = scenarioStore.activeAccount()
  const found = scenarioStore.orderDetails.find(
    item => item.order.orderNo === orderNo
      && item.order.userId === account.userId
      && item.order.orderType === 2,
  )
  if (!found) {
    return null
  }
  const normalized = cloneContractData(found)
  normalized.order.recharge = rechargeBlockFromLegacySnapshot(
    normalized.order.packageSnapshot,
    normalized.order.orderType,
  )
  return normalized
}

/**
 * U06 的数据源由路由合同显式指定；无 source 的深链一律返回 null，由页面读取真实 order API。
 * 这样固定 WO 快照可本地回看，同时不会用同号 Mock 覆盖真实后端订单。
 */
export function findLocalRechargeOrderForRoute(
  orderNo: string,
  source?: string,
): OrderDetail | null {
  if (source !== 'local-mock') {
    return null
  }
  if (currentMode('recharge') !== 'mock') {
    throw new ContractError('LOCAL_RECHARGE_DISABLED', '充值域已切换为真实接口，不能读取本地充值快照')
  }
  return findLocalRechargeOrder(orderNo)
}

export function listLocalRechargeOrders(): OrderDetail[] {
  const account = scenarioStore.activeAccount()
  return scenarioStore.orderDetails
    .filter(
      item => item.order.userId === account.userId
        && item.order.orderType === 2
        && !!item.order.mockMeta,
    )
    .map((item) => {
      const normalized = cloneContractData(item)
      normalized.order.recharge = rechargeBlockFromLegacySnapshot(
        normalized.order.packageSnapshot,
        normalized.order.orderType,
      )
      return normalized
    })
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
 * 数值字段解析：**必须同时接受 number 与十进制字符串**。
 *
 * 后端把所有 Long 序列化成字符串以避免 JS 的 53 位精度丢失（`packageId`、`payAmountFen`、
 * `waterMl`、`bonusAmountFen`、`orderAmountFen` 全是字符串；Integer 型的 `orderStatus`/
 * `payStatus`/`paySource` 才是数字）。此前这里只认 number，导致套餐页拿到后端正确响应后
 * 仍以「packageId 缺失或非法」整页 fail-closed——接口通了，页面却一个套餐都渲染不出来。
 *
 * 仍然严格：空串、小数、指数、前导零、非十进制一律拒绝，绝不 `Number()` 一把梭。
 */
function requireNumber(value: unknown, field: string): number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string' && /^(?:0|-?[1-9]\d*)$/.test(value)) {
    const parsed = Number(value)
    if (!Number.isSafeInteger(parsed)) {
      throw new ContractError('RECHARGE_FIELD_INVALID', `充值响应字段 ${field} 超出安全整数范围`)
    }
    return parsed
  }
  throw new ContractError('RECHARGE_FIELD_INVALID', `充值响应字段 ${field} 缺失或非法`)
}

/** ID 字段：保留字符串原样（不转 number，避免大 ID 精度丢失），只校验是正十进制。 */
function requireId(value: unknown, field: string): string {
  const raw = typeof value === 'number' ? String(value) : value
  if (typeof raw !== 'string' || !/^[1-9]\d*$/.test(raw)) {
    throw new ContractError('RECHARGE_FIELD_INVALID', `充值响应字段 ${field} 缺失或非法`)
  }
  return raw
}

function requireText(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new ContractError('RECHARGE_FIELD_INVALID', `充值响应字段 ${field} 缺失或非法`)
  }
  return value
}

function requireBoolean(value: unknown, field: string): boolean {
  if (typeof value !== 'boolean') {
    throw new ContractError('RECHARGE_FIELD_INVALID', `充值响应字段 ${field} 缺失或非法`)
  }
  return value
}

/**
 * 套餐归一化：金额/水量缺失一律拒绝而不是补 0。
 * 补 0 会让「500 元套餐」在页面上显示成 0 元，用户点下去却被扣真钱。
 */
export function normalizePackage(raw: PackageRaw): RechargePackage {
  return {
    id: requireId(raw.packageId, 'packageId'),
    packageName: requireText(raw.packageName, 'packageName'),
    payAmountFen: requireNumber(raw.payAmountFen, 'payAmountFen'),
    waterMl: requireNumber(raw.waterMl, 'waterMl'),
    // 必须走同一套解析：这里曾写成「不是 number 就取 0」，而后端 Long 是字符串，
    // 于是「50元充值(送5元)」在页面上显示成「赠送 ¥0.00」——用户看到的赠送额与实际到账不符。
    bonusAmountFen: requireNumber(raw.bonusAmountFen ?? 0, 'bonusAmountFen'),
    unitPriceSnap: requireText(raw.unitPriceSnap, 'unitPriceSnap'),
    expireDays: raw.expireDays == null ? null : normalizeRechargeExpireDays(raw.expireDays),
    packageStatus: 1,
    purchasable: requireBoolean(raw.purchasable, 'purchasable'),
  }
}

/**
 * 真实创单请求体：首次购卡必须彻底省略 cardId。
 *
 * 传 null、空串或字符串 "undefined" 会把坏请求误分流成购卡，因此除真正的 undefined 外
 * 一律拒绝，不能靠服务端把畸形值猜成某种模式。
 */
export function buildRechargeCreateBody(input: CreateRechargeOrderInput): Record<string, string> {
  const body: Record<string, string> = {
    packageId: String(input.packageId),
    requestId: input.requestId,
  }
  if (input.cardId !== undefined) {
    const cardId = String(input.cardId)
    if (!/^[1-9]\d*$/.test(cardId)) {
      throw new ContractError('RECHARGE_CARD_ID_INVALID', '水卡编号格式错误，不能创建充值订单')
    }
    body.cardId = cardId
  }
  return body
}

/**
 * 支付状态归一化：{@code retryable} 缺失时取 false。
 * 默认可重试会让前端在服务端已判定 MISMATCH 的单子上无限轮询，
 * 既刷屏又让用户以为「还在处理中」——宁可停下来显示异常。
 */
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

export const rechargeApi = selectAdapter(mockRechargeApi, realRechargeApi, 'recharge')
