import { ContractError } from './common'
import { normalizeId, optionalText, strictNonNegInt, textOf } from './mall'
import { withRealSession } from './real-session'
import { post } from './request'
import type { EntityId } from './common'

/**
 * 商城履约 API（E2E-09 S3-B）：用户侧物流回看与签收、配送员侧本人任务。
 * 形态口径与 mall-trade.ts 同源：Long 恒字符串、状态与节点裸数字，畸形值 fail-closed 抛 ContractError；
 * 归属恒由服务端按会话判定，本层不发送任何 userId/courierId。
 */

/** 履约任务状态（字典 1396）。 */
export type MallFulfillStatus = 1 | 2 | 3 | 4 | 5 | 6 | 7

/** 履约轨迹操作方（字典 1397）。 */
export type MallFulfillActor = 1 | 2 | 3 | 4

/** 签收方式（字典 1398）。 */
export type MallSignMethod = 1 | 2

/** 承运渠道（字典 1404）：0 未定 / 1 自营配送 / 2 第三方物流。 */
export type MallFulfillMode = 0 | 1 | 2

/**
 * 包裹状态（字典 1406），与后端 MallEnum.ShipmentStatus 逐值同源。白名单漏一个真实可达的值，
 * normalizeShipment 会 fail-closed 连带履约时间线与「确认收货」入口一起消失，订单再也走不完。
 */
export type MallShipmentStatus = 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8

const FULFILL_STATUS_VALUES: readonly MallFulfillStatus[] = [1, 2, 3, 4, 5, 6, 7]
const ACTOR_VALUES: readonly MallFulfillActor[] = [1, 2, 3, 4]
const FULFILL_MODE_VALUES: readonly MallFulfillMode[] = [0, 1, 2]
const SHIPMENT_STATUS_VALUES: readonly MallShipmentStatus[] = [1, 2, 3, 4, 5, 6, 7, 8]

export interface MallFulfillTrace {
  traceNode: MallFulfillStatus
  traceNodeName: string
  actorType: MallFulfillActor
  actorTypeName: string
  traceTime: string
  traceText: string
}

export interface MallShipmentTrace {
  eventState: string
  eventStateName: string
  eventTime: string
  eventDesc: string
}

export interface MallShipment {
  shipmentId: EntityId
  orderNo: string
  direction: number
  directionName: string
  fulfillMode: MallFulfillMode
  fulfillModeName: string
  providerCode?: string
  serviceCode?: string
  waybillNo?: string
  shipmentStatus: MallShipmentStatus
  shipmentStatusName: string
  createShipTime?: string
  pickupTime?: string
  deliverTime?: string
  logisticsTraces: MallShipmentTrace[]
}

export interface MallFulfill {
  orderNo: string
  fulfillStatus: MallFulfillStatus
  fulfillStatusName: string
  /** 承运渠道：自营看配送员，第三方看运单号与承运方轨迹。 */
  fulfillMode: MallFulfillMode
  fulfillModeName: string
  warehouseId: EntityId
  warehouseName?: string
  courierId?: EntityId
  courierName?: string
  courierPhone?: string
  receiverName: string
  receiverPhone: string
  receiverRegion: string
  receiverAddress: string
  pickTime?: string
  packTime?: string
  assignTime?: string
  fetchTime?: string
  arriveTime?: string
  signTime?: string
  signMethod?: MallSignMethod
  signRemark?: string
  /** 换货补发单：与普通订单同形履约，但不再向用户收款。 */
  exchangeReshipment: boolean
  timeline: MallFulfillTrace[]
}

export const mallFulfillEndpoints = {
  detail: '/mini/mall/fulfillment/detail',
  sign: '/mini/mall/fulfillment/sign',
  shipments: '/mini/mall/fulfillment/shipments',
  courierList: '/mini/mall/fulfillment/courier/list',
  courierDetail: '/mini/mall/fulfillment/courier/detail',
  courierFetch: '/mini/mall/fulfillment/courier/fetch',
  courierArrive: '/mini/mall/fulfillment/courier/arrive',
} as const

export const MALL_SIGN_REMARK_MAX = 200

function rowOf(raw: unknown): Record<string, unknown> {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new ContractError('MALL_FULFILL_BAD_SHAPE', '履约数据异常')
  }
  return raw as Record<string, unknown>
}

function enumOf<T extends number>(raw: unknown, allowed: readonly T[], field: string): T {
  const value = strictNonNegInt(raw)
  if (value === undefined || !allowed.includes(value as T)) {
    throw new ContractError('MALL_FULFILL_BAD_STATUS', `履约状态数据异常（${field}）`)
  }
  return value as T
}

function normalizeTrace(row: Record<string, unknown>): MallFulfillTrace {
  return {
    traceNode: enumOf(row.traceNode, FULFILL_STATUS_VALUES, 'traceNode'),
    traceNodeName: textOf(row.traceNodeName),
    actorType: enumOf(row.actorType, ACTOR_VALUES, 'actorType'),
    actorTypeName: textOf(row.actorTypeName),
    traceTime: textOf(row.traceTime),
    traceText: textOf(row.traceText),
  }
}

export function normalizeFulfill(raw: unknown): MallFulfill {
  const row = rowOf(raw)
  const timeline = Array.isArray(row.timeline) ? row.timeline : []
  const signMethodRaw = strictNonNegInt(row.signMethod)
  return {
    orderNo: textOf(row.orderNo),
    fulfillStatus: enumOf(row.fulfillStatus, FULFILL_STATUS_VALUES, 'fulfillStatus'),
    fulfillStatusName: textOf(row.fulfillStatusName),
    // 渠道缺失按「未定」：老数据没有这列，与"还没选渠道"在端上是同一件事；
    // 但出现 0/1/2 之外的值就是契约漂了，必须炸而不是猜
    fulfillMode:
      row.fulfillMode === null || row.fulfillMode === undefined
        ? 0
        : enumOf(row.fulfillMode, FULFILL_MODE_VALUES, 'fulfillMode'),
    fulfillModeName: textOf(row.fulfillModeName),
    warehouseId: normalizeId(row.warehouseId, 'warehouseId'),
    warehouseName: optionalText(row.warehouseName),
    courierId:
      row.courierId === null || row.courierId === undefined
        ? undefined
        : normalizeId(row.courierId, 'courierId'),
    courierName: optionalText(row.courierName),
    courierPhone: optionalText(row.courierPhone),
    receiverName: textOf(row.receiverName),
    receiverPhone: textOf(row.receiverPhone),
    receiverRegion: textOf(row.receiverRegion),
    receiverAddress: textOf(row.receiverAddress),
    pickTime: optionalText(row.pickTime),
    packTime: optionalText(row.packTime),
    assignTime: optionalText(row.assignTime),
    fetchTime: optionalText(row.fetchTime),
    arriveTime: optionalText(row.arriveTime),
    signTime: optionalText(row.signTime),
    // 签收方式只认 1/2；未知值不得默认成「本人签收」——那是在替用户作证
    signMethod:
      signMethodRaw === undefined
        ? undefined
        : enumOf(signMethodRaw, [1, 2] as const, 'signMethod'),
    signRemark: optionalText(row.signRemark),
    // 只认真布尔：字段缺失或形态不对一律按「不是换货」，宁可少提示也不错标
    exchangeReshipment: row.exchangeReshipment === true,
    timeline: (timeline as unknown[])
      .filter((item): item is Record<string, unknown> => typeof item === 'object' && item !== null)
      .map(normalizeTrace),
  }
}

function normalizeShipmentTrace(row: Record<string, unknown>): MallShipmentTrace {
  return {
    eventState: textOf(row.eventState),
    eventStateName: textOf(row.eventStateName),
    eventTime: textOf(row.eventTime),
    eventDesc: textOf(row.eventDesc),
  }
}

export function normalizeShipment(raw: unknown): MallShipment {
  const row = rowOf(raw)
  const traces = Array.isArray(row.logisticsTraces) ? row.logisticsTraces : []
  return {
    shipmentId: normalizeId(row.shipmentId, 'shipmentId'),
    orderNo: textOf(row.orderNo),
    direction: strictNonNegInt(row.direction) ?? 0,
    directionName: textOf(row.directionName),
    fulfillMode: enumOf(row.fulfillMode, FULFILL_MODE_VALUES, 'fulfillMode'),
    fulfillModeName: textOf(row.fulfillModeName),
    providerCode: optionalText(row.providerCode),
    serviceCode: optionalText(row.serviceCode),
    waybillNo: optionalText(row.waybillNo),
    shipmentStatus: enumOf(row.shipmentStatus, SHIPMENT_STATUS_VALUES, 'shipmentStatus'),
    shipmentStatusName: textOf(row.shipmentStatusName),
    createShipTime: optionalText(row.createShipTime),
    pickupTime: optionalText(row.pickupTime),
    deliverTime: optionalText(row.deliverTime),
    logisticsTraces: (traces as unknown[])
      .filter((item): item is Record<string, unknown> => typeof item === 'object' && item !== null)
      .map(normalizeShipmentTrace),
  }
}

export const mallFulfillApi = {
  /** 本人订单的履约详情（用户视角）。 */
  detail: (orderNo: string) =>
    withRealSession(async () =>
      normalizeFulfill(await post<unknown>(mallFulfillEndpoints.detail, { orderNo })),
    ),

  /** 用户签收：服务端在同一事务内推进任务与订单，签收时间由服务端生成。 */
  sign: (input: { orderNo: string, signMethod: MallSignMethod, signRemark?: string }) =>
    withRealSession(async () =>
      normalizeFulfill(await post<unknown>(mallFulfillEndpoints.sign, input)),
    ),

  /** 本人订单的出库包裹：第三方单在这里才有运单号与承运方轨迹。 */
  shipments: (orderNo: string) =>
    withRealSession(async () => {
      const raw = await post<unknown>(mallFulfillEndpoints.shipments, { orderNo })
      return (Array.isArray(raw) ? raw : []).map(normalizeShipment)
    }),

  /** 配送员本人商城任务列表。 */
  courierList: () =>
    withRealSession(async () => {
      const raw = await post<unknown>(mallFulfillEndpoints.courierList, {})
      return (Array.isArray(raw) ? raw : []).map(normalizeFulfill)
    }),

  courierDetail: (orderNo: string) =>
    withRealSession(async () =>
      normalizeFulfill(await post<unknown>(mallFulfillEndpoints.courierDetail, { orderNo })),
    ),

  courierFetch: (orderNo: string) =>
    withRealSession(async () =>
      normalizeFulfill(await post<unknown>(mallFulfillEndpoints.courierFetch, { orderNo })),
    ),

  courierArrive: (orderNo: string) =>
    withRealSession(async () =>
      normalizeFulfill(await post<unknown>(mallFulfillEndpoints.courierArrive, { orderNo })),
    ),
}
