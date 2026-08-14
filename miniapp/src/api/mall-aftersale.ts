import { ContractError } from './common'
import { normalizeFen, normalizeId, optionalText, strictNonNegInt, textOf } from './mall'
import { withRealSession } from './real-session'
import { post } from './request'
import type { EntityId, MoneyFen } from './common'

/**
 * 商城售后 API（E2E-09 S4）：申请、撤销、列表与详情。申请入参没有金额字段——应退金额由服务端
 * 按原订单不可变明细算出；归属恒由服务端按会话判定，本层不发送 userId；畸形数据 fail-closed 抛 ContractError。
 */

/** 售后状态（字典 1399）。 */
export type MallAfterSaleStatus = 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9
/** 售后类型（字典 1400）。 */
export type MallAfterSaleType = 1 | 2 | 3

const STATUS_VALUES: readonly MallAfterSaleStatus[] = [1, 2, 3, 4, 5, 6, 7, 8, 9]
const TYPE_VALUES: readonly MallAfterSaleType[] = [1, 2, 3]

export interface MallAfterSaleLine {
  orderItemId: EntityId
  skuId: EntityId
  productName: string
  skuName: string
  unitPriceFen: MoneyFen
  quantity: number
  itemAmountFen: MoneyFen
}

export interface MallAfterSaleTrace {
  traceNode: number
  traceNodeName: string
  actorTypeName: string
  traceTime: string
  traceText: string
}

export interface MallAfterSale {
  afterSaleNo: string
  orderNo: string
  afterSaleType: MallAfterSaleType
  afterSaleTypeName: string
  afterSaleStatus: MallAfterSaleStatus
  afterSaleStatusName: string
  refundAmountFen: MoneyFen
  applyReason: string
  applyTime: string
  inspectResultName?: string
  inspectRemark?: string
  rejectReason?: string
  finishTime?: string
  refundStatus?: number
  refundSuccessTime?: string
  exchangeOrderNo?: string
  lines: MallAfterSaleLine[]
  timeline: MallAfterSaleTrace[]
}

export interface MallAfterSaleApplyInput {
  requestId: string
  orderNo: string
  afterSaleType: MallAfterSaleType
  applyReason: string
  lines?: { orderItemId: EntityId, quantity: number }[]
}

export const mallAfterSaleEndpoints = {
  apply: '/mini/mall/aftersale/apply',
  cancel: '/mini/mall/aftersale/cancel',
  list: '/mini/mall/aftersale/list',
  detail: '/mini/mall/aftersale/detail',
} as const

export const MALL_AFTER_SALE_REASON_MAX = 200

function rowOf(raw: unknown): Record<string, unknown> {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new ContractError('MALL_AFTERSALE_BAD_SHAPE', '售后数据异常')
  }
  return raw as Record<string, unknown>
}

function enumOf<T extends number>(raw: unknown, allowed: readonly T[], field: string): T {
  const value = strictNonNegInt(raw)
  if (value === undefined || !allowed.includes(value as T)) {
    throw new ContractError('MALL_AFTERSALE_BAD_STATUS', `售后状态数据异常（${field}）`)
  }
  return value as T
}

function lineOf(row: Record<string, unknown>): MallAfterSaleLine {
  const quantity = strictNonNegInt(row.quantity)
  if (quantity === undefined || quantity <= 0) {
    throw new ContractError('MALL_AFTERSALE_BAD_QUANTITY', '售后数量数据异常')
  }
  return {
    orderItemId: normalizeId(row.orderItemId, 'orderItemId'),
    skuId: normalizeId(row.skuId, 'skuId'),
    productName: textOf(row.productName),
    skuName: textOf(row.skuName),
    unitPriceFen: normalizeFen(row.unitPriceFen, 'unitPriceFen'),
    quantity,
    itemAmountFen: normalizeFen(row.itemAmountFen, 'itemAmountFen'),
  }
}

export function normalizeAfterSale(raw: unknown): MallAfterSale {
  const row = rowOf(raw)
  const lines = Array.isArray(row.lines) ? row.lines : []
  const timeline = Array.isArray(row.timeline) ? row.timeline : []
  return {
    afterSaleNo: textOf(row.afterSaleNo),
    orderNo: textOf(row.orderNo),
    afterSaleType: enumOf(row.afterSaleType, TYPE_VALUES, 'afterSaleType'),
    afterSaleTypeName: textOf(row.afterSaleTypeName),
    afterSaleStatus: enumOf(row.afterSaleStatus, STATUS_VALUES, 'afterSaleStatus'),
    afterSaleStatusName: textOf(row.afterSaleStatusName),
    refundAmountFen: normalizeFen(row.refundAmountFen, 'refundAmountFen'),
    applyReason: textOf(row.applyReason),
    applyTime: textOf(row.applyTime),
    inspectResultName: optionalText(row.inspectResultName),
    inspectRemark: optionalText(row.inspectRemark),
    rejectReason: optionalText(row.rejectReason),
    finishTime: optionalText(row.finishTime),
    refundStatus: strictNonNegInt(row.refundStatus),
    refundSuccessTime: optionalText(row.refundSuccessTime),
    exchangeOrderNo: optionalText(row.exchangeOrderNo),
    lines: (lines as unknown[])
      .filter((item): item is Record<string, unknown> => typeof item === 'object' && item !== null)
      .map(lineOf),
    timeline: (timeline as unknown[])
      .filter((item): item is Record<string, unknown> => typeof item === 'object' && item !== null)
      .map(item => ({
        traceNode: strictNonNegInt(item.traceNode) ?? 0,
        traceNodeName: textOf(item.traceNodeName),
        actorTypeName: textOf(item.actorTypeName),
        traceTime: textOf(item.traceTime),
        traceText: textOf(item.traceText),
      })),
  }
}

export const mallAfterSaleApi = {
  apply: (input: MallAfterSaleApplyInput) =>
    withRealSession(async () =>
      normalizeAfterSale(await post<unknown>(mallAfterSaleEndpoints.apply, { ...input })),
    ),

  cancel: (afterSaleNo: string) =>
    withRealSession(async () =>
      normalizeAfterSale(await post<unknown>(mallAfterSaleEndpoints.cancel, { afterSaleNo })),
    ),

  list: () =>
    withRealSession(async () => {
      const raw = await post<unknown>(mallAfterSaleEndpoints.list, {})
      return (Array.isArray(raw) ? raw : []).map(normalizeAfterSale)
    }),

  detail: (afterSaleNo: string) =>
    withRealSession(async () =>
      normalizeAfterSale(await post<unknown>(mallAfterSaleEndpoints.detail, { afterSaleNo })),
    ),
}
