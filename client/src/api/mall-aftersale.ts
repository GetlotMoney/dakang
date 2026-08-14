import request from '@/utils/http'
import { normalizePage, strictNonNegInt } from './mall-normalize'

/** 与 mall.ts 同形的分页壳：那份是本地类型，未导出，故此处同义声明一次。 */
type PageResult<T> = { total: number; list: T[] }

/**
 * 商城售后接口（E2E-09 S4）。
 *
 * 后端 Long 出参恒为 string，本层原样透传不做 Number 转换。
 * **没有任何金额入参**：应退金额由服务端按原订单不可变明细算出，页面既不计算也不提交。
 */

/** 售后状态（字典 1399）。 */
export const MALL_AFTER_SALE_STATUS = {
  PENDING_AUDIT: 1,
  PENDING_RETURN: 2,
  PENDING_INSPECT: 3,
  REFUNDING: 4,
  EXCHANGING: 5,
  COMPLETED: 6,
  REJECTED: 7,
  NEED_MANUAL: 8,
  CANCELLED: 9
} as const

/** 质检结论（字典 1401）：只有 1 才回库。 */
export const MALL_INSPECT_RESULT = {
  PASS_RESELLABLE: 1,
  PASS_NOT_RESELLABLE: 2,
  REJECTED: 3
} as const

export interface MallAfterSaleLine {
  orderItemId: string
  skuId: string
  productName: string
  skuName: string
  unitPriceFen: string
  quantity: number
  itemAmountFen: string
}

export interface MallAfterSaleTrace {
  traceNode: number
  traceNodeName: string
  actorType: number
  actorTypeName: string
  traceTime: string
  traceText: string
}

export interface MallAfterSaleItem {
  afterSaleNo: string
  orderNo: string
  afterSaleType: number
  afterSaleTypeName: string
  afterSaleStatus: number
  afterSaleStatusName: string
  refundAmountFen: string
  applyReason: string
  applyTime: string
  inspectResult?: number
  inspectResultName?: string
  inspectRemark?: string
  rejectReason?: string
  finishTime?: string
  refundStatus?: number
  refundSuccessTime?: string
  exchangeOrderNo?: string
  lines?: MallAfterSaleLine[]
  timeline?: MallAfterSaleTrace[]
}

type RawRecord = Record<string, unknown>

const textOf = (raw: unknown): string => (typeof raw === 'string' ? raw : '')
const optTextOf = (raw: unknown): string | undefined =>
  typeof raw === 'string' && raw.length > 0 ? raw : undefined

/** 金额与 ID 只接受字符串：收到 number 说明契约漂了，宁可整次失败也不静默转换。 */
function idOf(raw: unknown, field: string): string {
  if (typeof raw !== 'string' || raw.length === 0) {
    throw new Error(`售后数据 ${field} 形态非法`)
  }
  return raw
}

function lineOf(row: RawRecord): MallAfterSaleLine {
  return {
    orderItemId: idOf(row.orderItemId, 'orderItemId'),
    skuId: idOf(row.skuId, 'skuId'),
    productName: textOf(row.productName),
    skuName: textOf(row.skuName),
    unitPriceFen: idOf(row.unitPriceFen, 'unitPriceFen'),
    quantity: strictNonNegInt(row.quantity) ?? 0,
    itemAmountFen: idOf(row.itemAmountFen, 'itemAmountFen')
  }
}

function traceOf(row: RawRecord): MallAfterSaleTrace {
  return {
    traceNode: strictNonNegInt(row.traceNode) ?? 0,
    traceNodeName: textOf(row.traceNodeName),
    actorType: strictNonNegInt(row.actorType) ?? 0,
    actorTypeName: textOf(row.actorTypeName),
    traceTime: textOf(row.traceTime),
    traceText: textOf(row.traceText)
  }
}

export function afterSaleRowOf(row: RawRecord): MallAfterSaleItem {
  const lines = Array.isArray(row.lines) ? (row.lines as RawRecord[]) : []
  const timeline = Array.isArray(row.timeline) ? (row.timeline as RawRecord[]) : []
  return {
    afterSaleNo: idOf(row.afterSaleNo, 'afterSaleNo'),
    orderNo: idOf(row.orderNo, 'orderNo'),
    afterSaleType: strictNonNegInt(row.afterSaleType) ?? 0,
    afterSaleTypeName: textOf(row.afterSaleTypeName),
    afterSaleStatus: strictNonNegInt(row.afterSaleStatus) ?? 0,
    afterSaleStatusName: textOf(row.afterSaleStatusName),
    refundAmountFen: idOf(row.refundAmountFen, 'refundAmountFen'),
    applyReason: textOf(row.applyReason),
    applyTime: textOf(row.applyTime),
    inspectResult: strictNonNegInt(row.inspectResult),
    inspectResultName: optTextOf(row.inspectResultName),
    inspectRemark: optTextOf(row.inspectRemark),
    rejectReason: optTextOf(row.rejectReason),
    finishTime: optTextOf(row.finishTime),
    refundStatus: strictNonNegInt(row.refundStatus),
    refundSuccessTime: optTextOf(row.refundSuccessTime),
    exchangeOrderNo: optTextOf(row.exchangeOrderNo),
    lines: lines.map(lineOf),
    timeline: timeline.map(traceOf)
  }
}

export async function fetchMallAfterSalePage(data: {
  current: number
  size: number
  afterSaleNo?: string
  orderNo?: string
  afterSaleStatus?: number
  afterSaleType?: number
}): Promise<PageResult<MallAfterSaleItem>> {
  const raw = await request.post<PageResult<MallAfterSaleItem>>({
    url: '/mall/aftersale/page',
    data
  })
  return normalizePage(raw, afterSaleRowOf)
}

export async function fetchMallAfterSaleDetail(afterSaleNo: string): Promise<MallAfterSaleItem> {
  return afterSaleRowOf(
    (await request.post<unknown>({
      url: '/mall/aftersale/detail',
      data: { afterSaleNo }
    })) as RawRecord
  )
}

export async function fetchMallAfterSaleAudit(data: {
  afterSaleNo: string
  approved: boolean
  remark?: string
}): Promise<MallAfterSaleItem> {
  return afterSaleRowOf(
    (await request.post<unknown>({ url: '/mall/aftersale/audit', data })) as RawRecord
  )
}

export async function fetchMallAfterSaleReceive(afterSaleNo: string): Promise<MallAfterSaleItem> {
  return afterSaleRowOf(
    (await request.post<unknown>({
      url: '/mall/aftersale/receive',
      data: { afterSaleNo }
    })) as RawRecord
  )
}

export async function fetchMallAfterSaleInspect(data: {
  afterSaleNo: string
  inspectResult: number
  inspectRemark: string
}): Promise<MallAfterSaleItem> {
  return afterSaleRowOf(
    (await request.post<unknown>({ url: '/mall/aftersale/inspect', data })) as RawRecord
  )
}

/**
 * 中止换货补发：释放已预占库存、取消补发单，售后单交回人工——
 * 否则补发送不出去时售后单永久停在「换货补发中」，预占也永不释放。
 */
export async function fetchMallAfterSaleAbortExchange(data: {
  afterSaleNo: string
  abortReason: string
}): Promise<MallAfterSaleItem> {
  return afterSaleRowOf(
    (await request.post<unknown>({ url: '/mall/aftersale/exchange-abort', data })) as RawRecord
  )
}

/** 发起模拟退款：仅隔离环境可用，关闭时后端直接拒绝。 */
export async function fetchMallAfterSaleRefundSim(afterSaleNo: string): Promise<MallAfterSaleItem> {
  return afterSaleRowOf(
    (await request.post<unknown>({
      url: '/mall/aftersale/refund-sim',
      data: { afterSaleNo }
    })) as RawRecord
  )
}
