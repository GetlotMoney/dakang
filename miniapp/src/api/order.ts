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
import type { DeliveryTask } from './delivery'
import { addSecondsToBusinessTime, cloneContractData, ContractError, nextBusinessTime, prototypeMeta } from './common'
import { requireLinkedDeliveryOrder } from './delivery-link'
import {
  DELIVERY_MEDIA_KEY_PATTERN,
  normalizeDeliveryAppeal,
  normalizeDeliveryTask,
} from './delivery-normalize'
import type { DeliveryAppealRaw, DeliveryTaskRaw } from './delivery-normalize'
import { withRealSession } from './real-session'
import { post } from './request'
import { currentMode, realAdapterPending, selectAdapter } from './runtime'
import { scenarioStore } from '@/scenario/store'

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

function findDetail(orderNo: string) {
  const userId = scenarioStore.activeAccount().userId
  const detail = scenarioStore.orderDetails.find(
    item => item.order.orderNo === orderNo && item.order.userId === userId,
  )
  if (!detail) {
    throw new ContractError('ORDER_NOT_FOUND', '订单不存在或无权访问')
  }
  return detail
}

const mockOrderApi: OrderApi = {
  async listMyOrders(query = {}) {
    const userId = scenarioStore.activeAccount().userId
    const current = query.current ?? 1
    const size = query.size ?? 20
    if (current <= 0 || size <= 0) {
      throw new ContractError('PAGE_QUERY_INVALID', '分页参数必须大于 0')
    }
    const matched = scenarioStore.orderDetails
      .map(item => item.order)
      .filter(item => item.userId === userId)
      .filter(item => !query.orderType || item.orderType === query.orderType)
      .filter(item => !query.orderStatus || item.orderStatus === query.orderStatus)
      .filter(item => !query.createTimeStart || item.createTime >= query.createTimeStart)
      .filter(item => !query.createTimeEnd || item.createTime <= query.createTimeEnd)
      .sort((left, right) => right.createTime.localeCompare(left.createTime))
    const start = (current - 1) * size
    const list = matched.slice(start, start + size)
    return cloneContractData({ list, total: matched.length })
  },
  async getOrderDetail(orderNo) {
    return cloneContractData(findDetail(orderNo))
  },
  async createWaterOrder(input) {
    if (input.planMl <= 0) {
      throw new ContractError('INVALID_VOLUME', '取水量必须大于 0')
    }
    const account = scenarioStore.activeAccount()
    // 提交前二次预检（蓝图 U04）：会话、设备、出水口、水种、卡状态、限额、余额逐项校验。
    const session = scenarioStore.findActiveScanSession(input.scanSessionId)
    if (!session) {
      throw new ContractError('SCAN_SESSION_EXPIRED', '扫码会话已失效，请重新扫码')
    }
    const device = scenarioStore.devices.find(item => item.deviceNo === session.deviceNo)
    const outlet = device?.outlets.find(item => item.outletId === session.outletId)
    if (!device || !outlet) {
      throw new ContractError('DEVICE_NOT_FOUND', '设备或出水口不存在')
    }
    if (outlet.waterTypeId !== input.waterTypeId) {
      throw new ContractError('WATER_TYPE_MISMATCH', '所选水种与出水口不一致，请重新确认')
    }
    if (device.onlineStatus !== 'ONLINE' || device.runStatus !== 'IDLE') {
      throw new ContractError('DEVICE_BLOCKED', '设备当前不可取水，请按预检提示处理')
    }
    const card = scenarioStore.cards.find(
      item => item.cardId === input.cardId && item.userId === account.userId,
    )
    if (!card) {
      throw new ContractError('CARD_NOT_FOUND', '水卡不存在或无权使用')
    }
    if (card.cardStatus !== 1) {
      throw new ContractError('CARD_NOT_USABLE', '水卡状态不可用（冻结/过期/注销），无法取水')
    }
    const maxAllowedMl = scenarioStore.waterEligibility.maxAllowedMl
    if (maxAllowedMl !== undefined && input.planMl > maxAllowedMl) {
      throw new ContractError('DAY_LIMIT_EXCEEDED', `超出单次可取上限 ${maxAllowedMl / 1000}L`)
    }
    const amountFen = Math.ceil(input.planMl / 1000) * outlet.unitPriceFenPerLiter
    if (input.payWay === 3 && card.balanceMl < input.planMl) {
      throw new ContractError('INSUFFICIENT_WATER', '水卡剩余水量不足')
    }
    if (input.payWay === 2 && card.balanceFen < amountFen) {
      throw new ContractError('INSUFFICIENT_BALANCE', '水卡余额不足')
    }

    // 会话一次性消费（蓝图 §6.6），二次提交按已失效拒绝。
    scenarioStore.consumeScanSession(input.scanSessionId)
    const sequence = scenarioStore.nextOrderSequence()
    const orderNo = `MW20260716${sequence}`
    // 动作发生时间由统一逻辑时钟取得并推进；后续剧本节点相对它偏移（第五轮审计整改）。
    const base = scenarioStore.takeBusinessTime()
    const liters = input.planMl / 1000
    // 原型固定剧本：数据一次性生成终态，U05 只按时间轴播放展示，不提供伪造状态推进接口。
    const detail: OrderDetail = {
      order: {
        orderId: `MO-${sequence}`,
        orderNo,
        userId: account.userId,
        orderType: 1,
        orderStatus: 4,
        orderAmountFen: amountFen,
        payWay: input.payWay,
        stationId: device.stationId,
        stationName: device.stationName,
        deviceNo: device.deviceNo,
        cardId: card.cardId,
        planMl: input.planMl,
        actualMl: input.planMl,
        createTime: base,
        finishTime: addSecondsToBusinessTime(base, 10),
        mockMeta: { ...prototypeMeta },
      },
      commandNo: `MCMD-${sequence}`,
      commandStatus: 4,
      trace: [
        {
          node: 'created',
          label: '原型订单已创建',
          time: base,
          detail: '未发生真实扣减或设备指令',
          tone: 'primary',
        },
        {
          node: 'dispatch',
          label: '出水指令已下发（原型）',
          time: addSecondsToBusinessTime(base, 2),
          tone: 'info',
        },
        {
          node: 'ack',
          label: '设备已确认（原型 ACK）',
          time: addSecondsToBusinessTime(base, 4),
          tone: 'info',
        },
        {
          node: 'result',
          label: '出水完成（原型 result）',
          time: addSecondsToBusinessTime(base, 10),
          detail: `计划 ${liters}L，实际 ${liters}L`,
          tone: 'success',
        },
      ],
      flowCount: 0,
    }
    scenarioStore.orderDetails.unshift(detail)
    // 原型剧本一次性生成终态：把时钟推进到剧本最后节点，保证后续动作时间不早于已展示的完成时间。
    scenarioStore.advanceBusinessClock(addSecondsToBusinessTime(base, 10))
    scenarioStore.recordAudit('USER_BASE', 'order.water.create', 'order', orderNo, 'success', base)
    return cloneContractData(detail)
  },
  async createRechargeOrder(input) {
    if (input.amountFen <= 0) {
      throw new ContractError('INVALID_AMOUNT', '充值金额必须大于 0')
    }
    const account = scenarioStore.activeAccount()
    // 前置校验卡（蓝图 §10 U10 行）：归属与可用状态；注销卡不可充值，冻结/过期卡由客服处理后再充。
    const card = scenarioStore.cards.find(
      item => item.cardId === input.cardId && item.userId === account.userId,
    )
    if (!card) {
      throw new ContractError('CARD_NOT_FOUND', '水卡不存在或无权使用')
    }
    if (card.cardStatus !== 1) {
      throw new ContractError('CARD_NOT_USABLE', '水卡状态不可用（冻结/过期/注销），请先联系客服处理')
    }
    // 套餐快照按下单时口径固化（名称/售价/水量/赠送），退款折算以快照为准（REQ-061 契约预留）。
    const selectedPackage = input.packageId
      ? scenarioStore.packages.find(item => item.id === input.packageId)
      : undefined
    if (input.packageId && !selectedPackage) {
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
        orderAmountFen: input.amountFen,
        payWay: 1,
        cardId: card.cardId,
        packageSnapshot: selectedPackage
          ? JSON.stringify({
              packageId: selectedPackage.id,
              packageName: selectedPackage.packageName,
              payAmountFen: selectedPackage.payAmountFen,
              waterMl: selectedPackage.waterMl,
              bonusAmountFen: selectedPackage.bonusAmountFen,
              expireDays: selectedPackage.expireDays,
            })
          : undefined,
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
    scenarioStore.recordAudit('USER_BASE', 'order.recharge.create', 'order', orderNo, 'success', createTime)
    return cloneContractData(detail)
  },
  async getMyDeliveryAppeal(appealId) {
    const userId = scenarioStore.activeAccount().userId
    const appeal = scenarioStore.deliveryAppeals.find(
      item => item.appealId === appealId && item.userId === userId,
    )
    if (!appeal) {
      throw new ContractError('APPEAL_NOT_FOUND', '申诉不存在或无权访问')
    }
    return cloneContractData(appeal)
  },
  async getMyDeliveryTask(orderNo) {
    const detail = findDetail(orderNo)
    const task = scenarioStore.deliveryTasks.find(
      item => item.orderNo === detail.order.orderNo && item.userId === detail.order.userId,
    )
    return task ? cloneContractData(task) : null
  },
  async createDeliveryAppeal(input) {
    const account = scenarioStore.activeAccount()
    const orderDetail = findDetail(input.orderNo)
    const task = scenarioStore.deliveryTasks.find(
      item => item.taskNo === input.taskNo && item.orderNo === input.orderNo,
    )
    if (!task || task.userId !== account.userId) {
      throw new ContractError('TASK_NOT_FOUND', '配送任务不存在或无权访问')
    }
    // 第四轮审计整改：创建申诉前先过中立关联栅栏（orderType===3 + orderId/userId/deliveryTaskNo 共键一致），
    // 并要求订单已完成、任务已签收、无活动申诉、申诉时间落在签收后 24 小时窗口内；
    // 任一校验失败即拒绝，申诉/任务/订单/消息/审计乃至场景时钟全部零副作用。
    const linkedRecord = requireLinkedDeliveryOrder(task)
    if (linkedRecord.order.orderStatus !== 4) {
      throw new ContractError('APPEAL_ORDER_STATE_INVALID', '申诉要求关联订单处于已完成状态')
    }
    if (task.taskStatus !== 5 || !task.signTime) {
      throw new ContractError('APPEAL_NOT_ALLOWED', '只有已签收订单可以发起申诉')
    }
    const activeAppeal = scenarioStore.deliveryAppeals.find(
      item => item.taskNo === input.taskNo && item.appealStatus === 1,
    )
    if (activeAppeal) {
      throw new ContractError('APPEAL_ALREADY_EXISTS', '该任务已有待处理申诉')
    }
    if (!input.description.trim() || input.receivedCount < 0) {
      throw new ContractError('APPEAL_INPUT_INVALID', '申诉说明或实收数量不合法')
    }
    // 统一逻辑时钟：申诉动作时间由场景时钟派生，须落在 [signTime, signTime+24h]；
    // 校验通过后才推进时钟，之后的轨迹/消息/审计全部使用同一时间。
    const appealTime = nextBusinessTime({ floorTimes: [scenarioStore.now], offsetSeconds: 1 })
    const appealDeadline = addSecondsToBusinessTime(task.signTime, 24 * 60 * 60)
    if (appealTime < task.signTime || appealTime > appealDeadline) {
      throw new ContractError('APPEAL_WINDOW_EXPIRED', '申诉须在签收后 24 小时内发起')
    }
    scenarioStore.advanceBusinessClock(appealTime)
    const appeal: DeliveryAppeal = {
      appealId: `APPEAL-${scenarioStore.deliveryAppeals.length + 1}`,
      orderNo: input.orderNo,
      taskNo: input.taskNo,
      userId: account.userId,
      appealStatus: 1,
      reason: input.reason,
      description: input.description.trim(),
      receivedCount: input.receivedCount,
      evidenceRefs: [...input.evidenceRefs],
      createTime: appealTime,
    }
    scenarioStore.deliveryAppeals.push(appeal)
    task.taskStatus = 7
    task.version += 1
    orderDetail.appealId = appeal.appealId
    orderDetail.trace.push({
      node: 'appeal-created',
      label: '申诉已登记，任务转申诉中',
      time: appealTime,
      detail: '等待运营核验证据并裁决；小程序只消费裁决结果',
      tone: 'warning',
    })
    scenarioStore.pushMessage({
      accountId: account.accountId,
      domain: 'delivery',
      title: '配送申诉已登记',
      summary: `申诉 ${appeal.appealId} 等待运营裁决`,
      content: `您对订单 ${input.orderNo} 的申诉已登记，运营核验证据后将给出成立/驳回结果。`,
      channel: 'in-app',
      sendStatus: 4,
      sendTime: appealTime,
      unread: true,
      objectType: 'appeal',
      objectId: appeal.appealId,
      requiredCapability: 'USER_BASE',
      evidenceMode: 'prototype',
    })
    scenarioStore.recordAudit('USER_BASE', 'delivery.appeal.create', 'appeal', appeal.appealId, 'success', appealTime)
    return cloneContractData(appeal)
  },
}

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
    if (currentMode('delivery') !== 'real') {
      return mockOrderApi.getMyDeliveryAppeal(appealId)
    }
    return withRealSession(async () => {
      const raw = await post<DeliveryAppealRaw>(orderEndpoints.appealDetail, { appealId })
      return normalizeDeliveryAppeal(raw)
    })
  },
  async createDeliveryAppeal(input) {
    if (currentMode('delivery') !== 'real') {
      return mockOrderApi.createDeliveryAppeal(input)
    }
    // 举证引用必须是已上传换取的受控媒体键；本地路径/伪引用在发出前拒绝（与 delivery 域同口径）
    for (const ref of input.evidenceRefs) {
      if (!DELIVERY_MEDIA_KEY_PATTERN.test(ref)) {
        throw new ContractError('DELIVERY_MEDIA_NOT_UPLOADED', '申诉凭证必须先完成照片上传（受控媒体键缺失）')
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
    if (currentMode('delivery') !== 'real') {
      return mockOrderApi.getMyDeliveryTask(orderNo)
    }
    return withRealSession(async () => {
      // 无任务后端返回 null（code 0 data null），透传 null 与 mock 口径一致
      const raw = await post<DeliveryTaskRaw | null>(orderEndpoints.deliveryTaskDetail, { orderNo })
      return raw ? normalizeDeliveryTask(raw) : null
    })
  },
}

export const orderApi = selectAdapter(mockOrderApi, realOrderApi, 'order')
