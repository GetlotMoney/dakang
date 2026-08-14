import request from '@/utils/http'
import { strictNonNegInt } from './mall-normalize'

/**
 * 商城履约接口（E2E-09 S3-B）。
 *
 * 后端 Long 出参恒为 string，本层不做 Number 转换——订单号与各类 ID 一律按字符串透传，
 * 转成 Number 会在超过 2^53 时静默丢精度，页面上看不出来、点下去却操作到别的行。
 */

/** 履约任务状态（字典 1396），与后端 MallEnum.FulfillStatus 同值域。 */
export const MALL_FULFILL_STATUS = {
  PENDING_PICK: 1,
  PENDING_PACK: 2,
  PENDING_ASSIGN: 3,
  PENDING_FETCH: 4,
  DELIVERING: 5,
  ARRIVED: 6,
  SIGNED: 7
} as const

/** 承运渠道（字典 1404），与后端 MallEnum.FulfillMode 同值域。 */
export const MALL_FULFILL_MODE = {
  UNDECIDED: 0,
  SELF_DELIVERY: 1,
  THIRD_PARTY: 2
} as const

/**
 * 包裹状态（字典 1406），与后端 MallEnum.ShipmentStatus 逐值同源；
 * 漏掉任一值（尤其异常待人工）会被界面渲染成正常态。
 */
export const MALL_SHIPMENT_STATUS = {
  PENDING: 1,
  ACCEPTED: 2,
  PICKED_UP: 3,
  IN_TRANSIT: 4,
  DELIVERED: 5,
  SIGNED: 6,
  CANCELLED: 7,
  NEED_MANUAL: 8
} as const

export interface MallFulfillTrace {
  traceNode: number
  traceNodeName: string
  actorType: number
  actorTypeName: string
  traceTime: string
  traceText: string
}

export interface MallShipmentTraceNode {
  eventState: string
  eventStateName: string
  eventTime: string
  eventDesc: string
}

export interface MallShipmentLine {
  orderItemId: string
  skuId: string
  quantity: number
}

export interface MallShipment {
  shipmentId: string
  orderNo: string
  direction: number
  directionName: string
  fulfillMode: number
  fulfillModeName: string
  providerCode?: string
  serviceCode?: string
  waybillNo?: string
  shipmentStatus: number
  shipmentStatusName: string
  createShipTime?: string
  pickupTime?: string
  deliverTime?: string
  lines: MallShipmentLine[]
  logisticsTraces: MallShipmentTraceNode[]
}

export interface MallFulfillDetail {
  orderNo: string
  fulfillStatus: number
  fulfillStatusName: string
  /** 承运渠道：0 未定时两个入口都可点，一经冻结另一个必须消失。 */
  fulfillMode: number
  fulfillModeName: string
  warehouseId: string
  warehouseName?: string
  courierId?: string
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
  signMethod?: number
  signRemark?: string
  /** 换货补发单：前置仓拣货前需要知道这单不再向用户收款。 */
  exchangeReshipment: boolean
  timeline: MallFulfillTrace[]
}

export interface MallCourierCandidate {
  courierId: string
  courierName: string
  courierPhone: string
}

type RawRecord = Record<string, unknown>

const textOf = (raw: unknown): string => (typeof raw === 'string' ? raw : '')
const optTextOf = (raw: unknown): string | undefined =>
  typeof raw === 'string' && raw.length > 0 ? raw : undefined

/** ID 只接受字符串：后端全链 string，收到 number 说明契约漂了，宁可整次失败也不静默转换。 */
function idOf(raw: unknown, field: string): string {
  if (typeof raw !== 'string' || raw.length === 0) {
    throw new Error(`履约数据 ${field} 形态非法`)
  }
  return raw
}

function traceOf(row: RawRecord): MallFulfillTrace {
  return {
    traceNode: strictNonNegInt(row.traceNode) ?? 0,
    traceNodeName: textOf(row.traceNodeName),
    actorType: strictNonNegInt(row.actorType) ?? 0,
    actorTypeName: textOf(row.actorTypeName),
    traceTime: textOf(row.traceTime),
    traceText: textOf(row.traceText)
  }
}

function detailOf(raw: unknown): MallFulfillDetail {
  const row = (raw ?? {}) as RawRecord
  const timeline = Array.isArray(row.timeline) ? (row.timeline as RawRecord[]) : []
  return {
    orderNo: idOf(row.orderNo, 'orderNo'),
    fulfillStatus: strictNonNegInt(row.fulfillStatus) ?? 0,
    fulfillStatusName: textOf(row.fulfillStatusName),
    // 渠道缺失按「未定」而不是抛错：老数据的 0 与新字段缺失在页面上是同一种含义
    fulfillMode: strictNonNegInt(row.fulfillMode) ?? 0,
    fulfillModeName: textOf(row.fulfillModeName),
    warehouseId: idOf(row.warehouseId, 'warehouseId'),
    warehouseName: optTextOf(row.warehouseName),
    courierId: optTextOf(row.courierId),
    courierName: optTextOf(row.courierName),
    courierPhone: optTextOf(row.courierPhone),
    receiverName: textOf(row.receiverName),
    receiverPhone: textOf(row.receiverPhone),
    receiverRegion: textOf(row.receiverRegion),
    receiverAddress: textOf(row.receiverAddress),
    pickTime: optTextOf(row.pickTime),
    packTime: optTextOf(row.packTime),
    assignTime: optTextOf(row.assignTime),
    fetchTime: optTextOf(row.fetchTime),
    arriveTime: optTextOf(row.arriveTime),
    signTime: optTextOf(row.signTime),
    signMethod: strictNonNegInt(row.signMethod),
    signRemark: optTextOf(row.signRemark),
    // 只认真布尔：字段缺失或形态不对一律按「不是换货」，宁可少提示也不错标
    exchangeReshipment: row.exchangeReshipment === true,
    timeline: timeline.map(traceOf)
  }
}

export async function fetchMallFulfillDetail(orderNo: string): Promise<MallFulfillDetail> {
  return detailOf(
    await request.post<unknown>({ url: '/mall/fulfillment/detail', data: { orderNo } })
  )
}

export async function fetchMallFulfillPick(orderNo: string): Promise<MallFulfillDetail> {
  return detailOf(await request.post<unknown>({ url: '/mall/fulfillment/pick', data: { orderNo } }))
}

export async function fetchMallFulfillPack(orderNo: string): Promise<MallFulfillDetail> {
  return detailOf(await request.post<unknown>({ url: '/mall/fulfillment/pack', data: { orderNo } }))
}

export async function fetchMallCourierCandidates(orderNo: string): Promise<MallCourierCandidate[]> {
  const raw = await request.post<unknown>({
    url: '/mall/fulfillment/courier-candidates',
    data: { orderNo }
  })
  const rows = Array.isArray(raw) ? (raw as RawRecord[]) : []
  return rows.map((row) => ({
    courierId: idOf(row.courierId, 'courierId'),
    courierName: textOf(row.courierName),
    courierPhone: textOf(row.courierPhone)
  }))
}

export async function fetchMallFulfillAssign(data: {
  orderNo: string
  courierId: string
}): Promise<MallFulfillDetail> {
  return detailOf(await request.post<unknown>({ url: '/mall/fulfillment/assign', data }))
}

function traceNodeOf(row: RawRecord): MallShipmentTraceNode {
  return {
    eventState: textOf(row.eventState),
    eventStateName: textOf(row.eventStateName),
    eventTime: textOf(row.eventTime),
    eventDesc: textOf(row.eventDesc)
  }
}

function shipmentOf(raw: unknown): MallShipment {
  const row = (raw ?? {}) as RawRecord
  const lines = Array.isArray(row.lines) ? (row.lines as RawRecord[]) : []
  const traces = Array.isArray(row.logisticsTraces) ? (row.logisticsTraces as RawRecord[]) : []
  return {
    shipmentId: idOf(row.shipmentId, 'shipmentId'),
    orderNo: idOf(row.orderNo, 'orderNo'),
    direction: strictNonNegInt(row.direction) ?? 0,
    directionName: textOf(row.directionName),
    fulfillMode: strictNonNegInt(row.fulfillMode) ?? 0,
    fulfillModeName: textOf(row.fulfillModeName),
    providerCode: optTextOf(row.providerCode),
    serviceCode: optTextOf(row.serviceCode),
    waybillNo: optTextOf(row.waybillNo),
    shipmentStatus: strictNonNegInt(row.shipmentStatus) ?? 0,
    shipmentStatusName: textOf(row.shipmentStatusName),
    createShipTime: optTextOf(row.createShipTime),
    pickupTime: optTextOf(row.pickupTime),
    deliverTime: optTextOf(row.deliverTime),
    lines: lines.map((line) => ({
      orderItemId: idOf(line.orderItemId, 'orderItemId'),
      skuId: idOf(line.skuId, 'skuId'),
      quantity: strictNonNegInt(line.quantity) ?? 0
    })),
    logisticsTraces: traces.map(traceNodeOf)
  }
}

export interface MallLogisticsProvider {
  providerCode: string
  providerName: string
}

export async function fetchMallShipmentProviders(): Promise<MallLogisticsProvider[]> {
  const raw = await request.post<unknown>({ url: '/mall/fulfillment/shipment/providers' })
  const rows = Array.isArray(raw) ? (raw as RawRecord[]) : []
  return rows.map((row) => ({
    providerCode: idOf(row.providerCode, 'providerCode'),
    providerName: textOf(row.providerName)
  }))
}

export async function fetchMallShipments(orderNo: string): Promise<MallShipment[]> {
  const raw = await request.post<unknown>({
    url: '/mall/fulfillment/shipment/list',
    data: { orderNo }
  })
  const rows = Array.isArray(raw) ? (raw as RawRecord[]) : []
  return rows.map(shipmentOf)
}

export async function fetchMallShipmentCreate(data: {
  orderNo: string
  providerCode: string
}): Promise<MallFulfillDetail> {
  return detailOf(await request.post<unknown>({ url: '/mall/fulfillment/shipment/create', data }))
}
