import type {
  BackendPageData,
  BusinessTime,
  EntityId,
  MockMeta,
  MoneyFen,
  PageQuery,
  PageResult,
  VolumeMl,
} from './common'
import type { AfterSaleCancelEligibility, AfterSaleProgress } from './after-sale'
import type { DeliveryTask } from './delivery'
import { normalizeAfterSaleProgress, normalizeCancelEligibility } from './after-sale'
import { ContractError } from './common'
import {
  DELIVERY_MEDIA_KEY_PATTERN,
  normalizeDeliveryAppeal,
  normalizeDeliveryTask,
} from './delivery-normalize'
import type { DeliveryAppealRaw, DeliveryTaskRaw } from './delivery-normalize'
import { withRealSession } from './real-session'
import { post } from './request'
import { realAdapterPending } from './runtime'

export type OrderType = 1 | 2 | 3
export type OrderStatus = 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8
export type PayWay = 1 | 2 | 3

/** 充值订单结构化详情。snapshotValid=false 时其余字段一律为空，页面必须提示数据异常。 */
export interface RechargeDetailBlock {
  snapshotValid: boolean
  /** FIRST_CARD 表示本单是首次购卡；缺省表示已有卡充值。 */
  purchaseMode?: 'FIRST_CARD'
  packageName?: string
  payAmountFen?: MoneyFen
  waterMl?: VolumeMl
  bonusAmountFen?: MoneyFen
  expireDays?: number | null
  payStatus?: number
  paySource?: number
  processingStatus?: string
  flowAmountChange?: MoneyFen
  flowMlChange?: VolumeMl
  flowAmountAfter?: MoneyFen
  flowMlAfter?: VolumeMl
  cardBalanceFen?: MoneyFen
  cardBalanceMl?: VolumeMl
  cardExpireTime?: string
  /** 首次购卡完成后由发卡结果回填；待支付时保持缺省。 */
  cardId?: EntityId
  cardNo?: string
}

export interface OrderItem {
  orderId: EntityId
  orderNo: string
  userId: EntityId
  orderType: OrderType
  orderStatus: OrderStatus
  orderAmountFen: MoneyFen
  payWay: PayWay
  stationId?: EntityId
  stationName?: string
  deviceNo?: string
  cardId?: EntityId
  planMl?: VolumeMl
  actualMl?: VolumeMl
  packageSnapshot?: string
  /**
   * 充值订单结构化详情（契约 v2 §9.2）。
   * 页面**只消费本区块，不解析 packageSnapshot**——快照的合法性判定只在服务端做一次，
   * 复制到前端再实现一遍必然走样，而快照是充值的凭据，两边不一致就是金额对不上。
   * Mock 与历史快照由本模块的适配层统一归一化成同一形状，页面无需感知来源。
   */
  recharge?: RechargeDetailBlock
  createTime: BusinessTime
  finishTime?: BusinessTime
  mockMeta?: MockMeta
}

export interface OrderTraceNode {
  node: string
  label: string
  time?: BusinessTime
  detail?: string
  tone: 'primary' | 'success' | 'warning' | 'danger' | 'info'
}

export interface OrderDetail {
  order: OrderItem
  commandNo?: string
  commandStatus?: number
  trace: OrderTraceNode[]
  flowCount: number
  deliveryTaskNo?: string
  appealId?: EntityId
  /**
   * 售后进度（E2E-04 包E，只读）。缺省 = 服务端未下发本单售后动作，
   * 页面隐藏售后区块；**不得由订单状态、金额或数量本地推算出一份售后结论**。
   */
  afterSale?: AfterSaleProgress
  /**
   * 待接单取消资格。缺省 = 服务端未下发，取消入口一律不显示。
   * 判定逻辑（任务是否仍待接单、是否本人、是否有在途售后）只存在于服务端事务内。
   */
  cancelEligibility?: AfterSaleCancelEligibility
}

export interface OrderQuery {
  orderType?: OrderType
  orderStatus?: OrderStatus
  createTimeStart?: BusinessTime
  createTimeEnd?: BusinessTime
  current?: PageQuery['current']
  size?: PageQuery['size']
}

/** 申诉状态(1352)：1待处理 2成立待补偿 3不成立驳回 4已撤销 5补送待执行（PC 裁决三种终态之一）。 */
export type AppealStatus = 1 | 2 | 3 | 4 | 5

/** 配送员针对申诉追加的举证材料（S08：D03 内追加说明/凭证）。 */
export interface CourierAppealEvidence {
  description: string
  evidenceRefs: string[]
  time: BusinessTime
}

export interface DeliveryAppeal {
  appealId: EntityId
  orderNo: string
  taskNo: string
  userId: EntityId
  appealStatus: AppealStatus
  reason: 'QUANTITY' | 'QUALITY' | 'DAMAGE' | 'PLACEMENT' | 'OTHER'
  description: string
  receivedCount: number
  evidenceRefs: string[]
  createTime: BusinessTime
  decisionSummary?: string
  courierEvidences?: CourierAppealEvidence[]
}

export interface CreateDeliveryAppealInput {
  orderNo: string
  taskNo: string
  reason: DeliveryAppeal['reason']
  description: string
  receivedCount: number
  evidenceRefs: string[]
}

export interface CreateWaterOrderInput {
  scanSessionId: string
  cardId: EntityId
  waterTypeId: EntityId
  planMl: VolumeMl
  payWay: 2 | 3
}

export interface CreateRechargeOrderInput {
  cardId: EntityId
  packageId?: EntityId
  amountFen: MoneyFen
}

export interface OrderApi {
  listMyOrders: (query?: OrderQuery) => Promise<PageResult<OrderItem>>
  getOrderDetail: (orderNo: string) => Promise<OrderDetail>
  createWaterOrder: (input: CreateWaterOrderInput) => Promise<OrderDetail>
  createRechargeOrder: (input: CreateRechargeOrderInput) => Promise<OrderDetail>
  getMyDeliveryAppeal: (appealId: EntityId) => Promise<DeliveryAppeal>
  createDeliveryAppeal: (input: CreateDeliveryAppealInput) => Promise<DeliveryAppeal>
  /** 消费者视角查看本人订单的配送任务（U06 三照/时间线证据），与配送员端 getTaskDetail 数据范围互不越权。 */
  getMyDeliveryTask: (orderNo: string) => Promise<DeliveryTask | null>
}

export const orderEndpoints = {
  list: '/mini/order/page',
  detail: '/mini/order/detail',
  createWater: '/mini/order/water/create',
  createRecharge: '/mini/order/recharge/create',
  appealDetail: '/mini/order/appeal/detail',
  appealCreate: '/mini/order/appeal/create',
  deliveryTaskDetail: '/mini/order/delivery-task/detail',
} as const

/**
 * 后端订单项原始返回：后端全局 Jackson 将 Long 序列化为字符串（防 JS 精度丢失），
 * 故 orderId/userId/orderAmountFen/stationId/cardId/planMl/actualMl 到达前端是数值字符串；
 * orderType/orderStatus/payWay 为 Integer 仍是数字；可空字段后端返回 null。
 */
interface OrderItemRaw {
  orderId: string | number
  orderNo: string
  userId: string | number
  orderType: number
  orderStatus: number
  orderAmountFen: string | number
  payWay: number
  stationId?: string | number | null
  stationName?: string | null
  deviceNo?: string | null
  cardId?: string | number | null
  planMl?: string | number | null
  actualMl?: string | number | null
  packageSnapshot?: string | null
  recharge?: unknown
  createTime: string
  finishTime?: string | null
}

interface OrderTraceNodeRaw {
  node: string
  label: string
  time?: string | null
  detail?: string | null
  tone?: string | null
}

export interface OrderDetailRaw {
  order: OrderItemRaw
  commandNo?: string | null
  commandStatus?: number | null
  trace?: OrderTraceNodeRaw[] | null
  flowCount?: number | null
  deliveryTaskNo?: string | null
  appealId?: string | number | null
  /** 售后进度区块（E2E-04 包E）；当前后端未下发，形状校验在 after-sale.ts 一处完成。 */
  afterSale?: unknown
  /** 取消资格：结构化 {allowed,reason}；兼容布尔 cancellable 形态。 */
  cancelEligibility?: unknown
  cancellable?: unknown
}

/** EntityId(string) 归一化：Long→String 后已是字符串，null 归一化为 undefined。 */
function optionalId(value: string | number | null | undefined): EntityId | undefined {
  return value == null ? undefined : String(value)
}

/** 金额/水量归一化：后端 Long 序列化为数值字符串，转 number；null 归一化为 undefined。 */
function optionalNumber(value: string | number | null | undefined): number | undefined {
  return value == null ? undefined : Number(value)
}

function optionalString(value: string | null | undefined): string | undefined {
  return value == null ? undefined : value
}

/** 后端订单项 → 前端 OrderItem（严格按 order.ts 契约类型归一化，real 数据不含 mockMeta）。 */
function normalizeOrderItem(raw: OrderItemRaw): OrderItem {
  return {
    orderId: String(raw.orderId),
    orderNo: raw.orderNo,
    userId: String(raw.userId),
    orderType: raw.orderType as OrderType,
    orderStatus: raw.orderStatus as OrderStatus,
    orderAmountFen: Number(raw.orderAmountFen),
    payWay: raw.payWay as PayWay,
    stationId: optionalId(raw.stationId),
    stationName: optionalString(raw.stationName),
    deviceNo: optionalString(raw.deviceNo),
    cardId: optionalId(raw.cardId),
    planMl: optionalNumber(raw.planMl),
    actualMl: optionalNumber(raw.actualMl),
    packageSnapshot: optionalString(raw.packageSnapshot),
    // 接真：后端已按 §9.2 下发结构化区块；Mock/历史单：由本地快照归一化，页面两边同形
    recharge: normalizeRechargeBlock(raw)
      ?? rechargeBlockFromLegacySnapshot(optionalString(raw.packageSnapshot), raw.orderType as OrderType),
    createTime: raw.createTime,
    finishTime: optionalString(raw.finishTime),
  }
}

/** 数值字段：后端把 Long 序列化成字符串防精度丢失，两种形态都要接受，但拒绝任何畸形值。 */
function strictNumber(value: unknown): number | undefined {
  // 必须是安全整数：金额是分、水量是毫升、有效期是天，全都不存在小数形态。
  // 只判 isFinite 会让 1.5 这类值一路渲染出去（测试抓到过）。
  if (typeof value === 'number') {
    return Number.isSafeInteger(value) ? value : undefined
  }
  if (typeof value === 'string' && /^(?:0|-?[1-9]\d*)$/.test(value)) {
    const n = Number(value)
    return Number.isSafeInteger(n) ? n : undefined
  }
  return undefined
}

const RECHARGE_LIMITS = {
  packageNameLength: 50,
  payAmountFen: 1_000_000,
  waterMl: 50_000_000,
  bonusAmountFen: 1_000_000,
  expireDays: 3650,
} as const

const RECHARGE_PROCESSING_STATUSES = new Set([
  'WAITING_PAYMENT',
  'PENDING',
  'PROCESSING',
  'PROCESSED',
  'RETRY_WAIT',
  'RECONCILIATION_REQUIRED',
])

function requiredRechargeName(value: unknown): string | undefined {
  if (typeof value !== 'string') {
    return undefined
  }
  const normalized = value.trim()
  return normalized.length >= 1 && normalized.length <= RECHARGE_LIMITS.packageNameLength
    ? normalized
    : undefined
}

function optionalRechargeInteger(
  value: unknown,
  maximum: number = Number.MAX_SAFE_INTEGER,
): number | undefined | 'invalid' {
  if (value == null) {
    return undefined
  }
  const normalized = strictNumber(value)
  return normalized != null && normalized >= 0 && normalized <= maximum ? normalized : 'invalid'
}

function optionalRechargeText(value: unknown): string | undefined | 'invalid' {
  if (value == null) {
    return undefined
  }
  return typeof value === 'string' && value.trim() ? value.trim() : 'invalid'
}

function optionalRechargeId(value: unknown): EntityId | undefined | 'invalid' {
  if (value == null) {
    return undefined
  }
  if (typeof value === 'string' && /^[1-9]\d*$/.test(value)) {
    return value
  }
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) {
    return String(value)
  }
  return 'invalid'
}

function isBusinessTime(value: string): boolean {
  return /^\d{14}$/.test(value)
}

/** 后端 §9.2 结构化区块。缺字段一律留空而不是补 0——补 0 会把 100 元套餐显示成 0 元。 */
export function normalizeRechargeBlock(raw: OrderItemRaw): RechargeDetailBlock | undefined {
  const value = raw.recharge
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return undefined
  }
  const b = value as Record<string, unknown>
  if (b.snapshotValid !== true) {
    return { snapshotValid: false }
  }

  const packageName = requiredRechargeName(b.packageName)
  const payAmountFen = strictNumber(b.payAmountFen)
  const waterMl = strictNumber(b.waterMl)
  const bonusAmountFen = strictNumber(b.bonusAmountFen)
  const hasExpireDays = Object.prototype.hasOwnProperty.call(b, 'expireDays')
  const expireDays = b.expireDays == null ? null : strictNumber(b.expireDays)
  if (!packageName
    || payAmountFen == null || payAmountFen < 1 || payAmountFen > RECHARGE_LIMITS.payAmountFen
    || waterMl == null || waterMl < 0 || waterMl > RECHARGE_LIMITS.waterMl
    || bonusAmountFen == null || bonusAmountFen < 0 || bonusAmountFen > RECHARGE_LIMITS.bonusAmountFen
    || !hasExpireDays || b.expireDays === undefined
    || (b.expireDays != null && (expireDays == null || expireDays < 1 || expireDays > RECHARGE_LIMITS.expireDays))) {
    return { snapshotValid: false }
  }

  const payStatus = optionalRechargeInteger(b.payStatus, 4)
  const paySource = optionalRechargeInteger(b.paySource, 2)
  const flowAmountChange = optionalRechargeInteger(b.flowAmountChange)
  const flowMlChange = optionalRechargeInteger(b.flowMlChange)
  const flowAmountAfter = optionalRechargeInteger(b.flowAmountAfter)
  const flowMlAfter = optionalRechargeInteger(b.flowMlAfter)
  const cardBalanceFen = optionalRechargeInteger(b.cardBalanceFen)
  const cardBalanceMl = optionalRechargeInteger(b.cardBalanceMl)
  const processingStatus = optionalRechargeText(b.processingStatus)
  const cardExpireTime = optionalRechargeText(b.cardExpireTime)
  const purchaseMode = optionalRechargeText(b.purchaseMode)
  const cardId = optionalRechargeId(b.cardId)
  const cardNo = optionalRechargeText(b.cardNo)
  if ([payStatus, paySource, flowAmountChange, flowMlChange, flowAmountAfter, flowMlAfter, cardBalanceFen, cardBalanceMl]
    .includes('invalid')
    || processingStatus === 'invalid'
    || (typeof processingStatus === 'string' && !RECHARGE_PROCESSING_STATUSES.has(processingStatus))
    || cardExpireTime === 'invalid'
    || purchaseMode === 'invalid'
    || (purchaseMode !== undefined && purchaseMode !== 'FIRST_CARD')
    || cardId === 'invalid'
    || cardNo === 'invalid'
    || (typeof cardNo === 'string' && cardNo.length > 32)
    || ((cardId === undefined) !== (cardNo === undefined))
    || (typeof payStatus === 'number' && payStatus < 1)
    || (typeof paySource === 'number' && paySource < 1)
    || (typeof cardExpireTime === 'string' && !isBusinessTime(cardExpireTime))) {
    return { snapshotValid: false }
  }
  return {
    snapshotValid: true,
    purchaseMode: purchaseMode as 'FIRST_CARD' | undefined,
    packageName,
    payAmountFen,
    waterMl,
    bonusAmountFen,
    expireDays,
    payStatus: payStatus as number | undefined,
    paySource: paySource as number | undefined,
    processingStatus: processingStatus as string | undefined,
    flowAmountChange: flowAmountChange as number | undefined,
    flowMlChange: flowMlChange as number | undefined,
    flowAmountAfter: flowAmountAfter as number | undefined,
    flowMlAfter: flowMlAfter as number | undefined,
    cardBalanceFen: cardBalanceFen as number | undefined,
    cardBalanceMl: cardBalanceMl as number | undefined,
    cardExpireTime: cardExpireTime as string | undefined,
    cardId: cardId as EntityId | undefined,
    cardNo: cardNo as string | undefined,
  }
}

/**
 * 历史/Mock 快照采用统一兼容方案归一化。
 *
 * 历史快照用的是 `payAmountFen`/`bonusAmountFen`，v2 用的是 `payAmount`/`bonusAmount`。
 * 早期页面直接读 v2 字段名，历史单于是显示成 ¥NaN、赠送金额整个丢失。
 * 这里把两代字段收敛到同一形状，**并对每个值做严格校验**——
 * 任一必需字段缺失或畸形即整体判为 snapshotValid=false，不允许残缺值拼出一个看起来正常的订单。
 */
export function rechargeBlockFromLegacySnapshot(
  snapshot: string | undefined,
  orderType: OrderType,
): RechargeDetailBlock | undefined {
  if (orderType !== 2) {
    return undefined
  }
  if (!snapshot) {
    return { snapshotValid: false }
  }
  let parsed: Record<string, unknown>
  try {
    const value: unknown = JSON.parse(snapshot)
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      return { snapshotValid: false }
    }
    parsed = value as Record<string, unknown>
  }
  catch {
    return { snapshotValid: false }
  }
  const packageName = requiredRechargeName(parsed.packageName)
  // 两代字段名并存：v2 用 payAmount/bonusAmount，历史 Mock 用 payAmountFen/bonusAmountFen
  const payAmountFen = strictNumber(parsed.payAmount ?? parsed.payAmountFen)
  const waterMl = strictNumber(parsed.waterMl)
  const bonusAmountFen = strictNumber(parsed.bonusAmount ?? parsed.bonusAmountFen)
  const rawDays = parsed.expireDays
  const expireDays = rawDays == null ? null : strictNumber(rawDays)
  const daysValid = rawDays == null
    || (expireDays != null && expireDays > 0 && expireDays <= RECHARGE_LIMITS.expireDays)
  const versionValid = parsed.schemaVersion == null || parsed.schemaVersion === 'L2_V2'
  if (!versionValid || !packageName
    || payAmountFen == null || payAmountFen < 1 || payAmountFen > RECHARGE_LIMITS.payAmountFen
    || waterMl == null || waterMl < 0 || waterMl > RECHARGE_LIMITS.waterMl
    || bonusAmountFen == null || bonusAmountFen < 0 || bonusAmountFen > RECHARGE_LIMITS.bonusAmountFen
    || !daysValid) {
    return { snapshotValid: false }
  }
  return { snapshotValid: true, packageName, payAmountFen, waterMl, bonusAmountFen, expireDays }
}

/** 导出供 delivery 域创单响应复用（订单区块与 /mini/order/detail 完全同构，归一化只此一份）。 */
export function normalizeOrderDetail(raw: OrderDetailRaw): OrderDetail {
  return {
    order: normalizeOrderItem(raw.order),
    commandNo: optionalString(raw.commandNo),
    commandStatus: raw.commandStatus == null ? undefined : Number(raw.commandStatus),
    trace: (raw.trace ?? []).map(node => ({
      node: node.node,
      label: node.label,
      time: optionalString(node.time),
      detail: optionalString(node.detail),
      // 后端 tone 取值 info/success/warning/danger，是前端联合类型子集，直接透传。
      tone: (node.tone ?? 'info') as OrderTraceNode['tone'],
    })),
    flowCount: Number(raw.flowCount ?? 0),
    deliveryTaskNo: optionalString(raw.deliveryTaskNo),
    appealId: optionalId(raw.appealId),
    // 售后区块与取消资格：服务端下发才有值，畸形数据整块丢弃（after-sale.ts fail-closed）
    afterSale: normalizeAfterSaleProgress(raw.afterSale),
    cancelEligibility: normalizeCancelEligibility(raw.cancelEligibility ?? raw.cancellable),
  }
}

/**
 * order 域真实适配器（L1e-MP 扫码取水下单链）。
 *
 * 仅 water/create、page、detail 三接口接真（后端 /mini/order/* 已就绪）；
 * appeal/delivery-task 读路径暂委托 mock（对真实订单返回空或明确报错，不制造假数据）；
 * 充值创建为写路径，L2 后端未建时显式 pending——Mock 假单会在 U06 真实查单处 404（2026-07-20 收口轮）。
 */
const realOrderApi: OrderApi = {
  async listMyOrders(query = {}) {
    return withRealSession(async () => {
      const raw = await post<BackendPageData<OrderItemRaw>>(orderEndpoints.list, {
        current: query.current ?? 1,
        size: query.size ?? 20,
        orderType: query.orderType,
        orderStatus: query.orderStatus,
      })
      // 后端 total 为 Long→字符串，归一化为 number 贴合 PageResult 契约。
      return {
        list: (raw.list ?? []).map(normalizeOrderItem),
        total: Number(raw.total ?? 0),
      } satisfies PageResult<OrderItem>
    })
  },
  async getOrderDetail(orderNo) {
    return withRealSession(async () => {
      const raw = await post<OrderDetailRaw>(orderEndpoints.detail, { orderNo })
      return normalizeOrderDetail(raw)
    })
  },
  async createWaterOrder(input) {
    // scanSessionId 兼作幂等 requestId：同会话重复提交由后端返同单（铁律1/2）。
    return withRealSession(async () => {
      const raw = await post<OrderDetailRaw>(orderEndpoints.createWater, {
        scanSessionId: input.scanSessionId,
        cardId: input.cardId,
        waterTypeId: input.waterTypeId,
        planMl: input.planMl,
        payWay: input.payWay,
      })
      return normalizeOrderDetail(raw)
    })
  },
  // 充值创建（写路径）不得委托 mock：order 域 real 下 Mock 单跳 U06 真实查单必 404（2026-07-20 收口轮 P0-3），
  // L2 后端就绪前显式 pending，宁可明确阻断也不给假成功。
  createRechargeOrder: () => realAdapterPending('创建充值订单', orderEndpoints.createRecharge),
  // 用户侧配送视图三接口按 delivery 域二次分流（E2E-03 包B）：delivery=real 走 /mini/order/**
  // 真实端点；delivery 仍为 mock 时保持原委托——读路径对真实订单安全返回空/明确报错，
  // 申诉创建（写路径）在 mock 域内也只会作用于 Mock 订单，不会对真实订单造假申诉。
  async getMyDeliveryAppeal(appealId) {
    return withRealSession(async () => {
      const raw = await post<DeliveryAppealRaw>(orderEndpoints.appealDetail, { appealId })
      return normalizeDeliveryAppeal(raw)
    })
  },
  async createDeliveryAppeal(input) {
    // 举证引用必须是已上传换取的受控媒体键；本地路径/伪引用在发出前拒绝（与 delivery 域同口径）
    for (const ref of input.evidenceRefs) {
      if (!DELIVERY_MEDIA_KEY_PATTERN.test(ref)) {
        throw new ContractError('DELIVERY_MEDIA_NOT_UPLOADED', '请先完成申诉凭证照片上传')
      }
    }
    return withRealSession(async () => {
      const raw = await post<DeliveryAppealRaw>(orderEndpoints.appealCreate, {
        orderNo: input.orderNo,
        taskNo: input.taskNo,
        reason: input.reason,
        description: input.description,
        receivedCount: input.receivedCount,
        evidenceRefs: input.evidenceRefs,
      })
      return normalizeDeliveryAppeal(raw)
    })
  },
  async getMyDeliveryTask(orderNo) {
    return withRealSession(async () => {
      // 无任务后端返回 null（code 0 data null），透传 null 与 mock 口径一致
      const raw = await post<DeliveryTaskRaw | null>(orderEndpoints.deliveryTaskDetail, { orderNo })
      return raw ? normalizeDeliveryTask(raw) : null
    })
  },
}

export const orderApi = realOrderApi
