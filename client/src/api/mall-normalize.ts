import type {
  MallOrderDetail,
  MallOrderItem,
  MallOrderLine,
  MallProductDetail,
  MallProductItem,
  MallSkuItem,
  MallStockAdjustResult,
  MallStockFlowItem,
  MallStockItem
} from './mall'

/**
 * 商城响应运行时归一化（R1-P0-2）。
 *
 * 后端 JacksonConfig 对 Long/long 全局 ToStringSerializer：分页 total、金额（分）、
 * 库存数量、流水增减/结果、SKU 重量、skuCount 等 Long 字段在真实响应里都是十进制
 * 字符串（"2400"），Integer 字段（version/status/sort/flowType）才是 number。
 * TS 类型标注挡不住运行时形态；本模块把两种形态统一为 number，畸形值 fail-closed
 * 抛错，绝不把 NaN/错值交给页面。纯函数、零运行时依赖（类型经 import type 回引），
 * 供 mall.ts 与单测共用。
 */

type RawRecord = Record<string, unknown>

/** 非负安全整数：接受 number 或规范十进制字符串；拒绝小数/负数/科学计数/前导零/正负号/空串/超安全整数。 */
export function strictNonNegInt(raw: unknown): number | undefined {
  if (typeof raw === 'number') {
    return Number.isSafeInteger(raw) && raw >= 0 ? raw : undefined
  }
  if (typeof raw === 'string' && /^(?:0|[1-9]\d*)$/.test(raw)) {
    const n = Number(raw)
    return Number.isSafeInteger(n) ? n : undefined
  }
  return undefined
}

/** 带符号安全整数：流水增减列出库/盘点调减为负（服务端存 -N）；"-0"、前导零仍拒绝。 */
export function strictSignedInt(raw: unknown): number | undefined {
  if (typeof raw === 'number') {
    return Number.isSafeInteger(raw) ? raw : undefined
  }
  if (typeof raw === 'string' && /^(?:0|-?[1-9]\d*)$/.test(raw)) {
    const n = Number(raw)
    return Number.isSafeInteger(n) ? n : undefined
  }
  return undefined
}

export function requiredCount(raw: unknown, field: string): number {
  const n = strictNonNegInt(raw)
  if (n === undefined) {
    throw new Error(`商城数据异常：${field} 形态非法（${JSON.stringify(raw)}）`)
  }
  return n
}

export function requiredDelta(raw: unknown, field: string): number {
  const n = strictSignedInt(raw)
  if (n === undefined) {
    throw new Error(`商城数据异常：${field} 形态非法（${JSON.stringify(raw)}）`)
  }
  return n
}

export function optionalCount(raw: unknown, field: string): number | undefined {
  if (raw === null || raw === undefined) {
    return undefined
  }
  return requiredCount(raw, field)
}

/** 分页壳归一：total 是 Long 字符串；行归一交给 mapRow，行内非法=整页失败。 */
export function normalizePage<T>(
  raw: unknown,
  mapRow: (row: RawRecord) => T
): {
  total: number
  list: T[]
} {
  if (raw === null || raw === undefined || typeof raw !== 'object') {
    throw new Error('商城数据异常：分页响应缺失')
  }
  const page = raw as RawRecord
  const listRaw = Array.isArray(page.list) ? page.list : []
  return {
    total: requiredCount(page.total, 'total'),
    list: listRaw
      .filter((row): row is RawRecord => typeof row === 'object' && row !== null)
      .map(mapRow)
  }
}

export function productRowOf(row: RawRecord): MallProductItem {
  return {
    ...(row as unknown as MallProductItem),
    skuCount: optionalCount(row.skuCount, 'skuCount')
  }
}

export function skuRowOf(row: RawRecord): MallSkuItem {
  return {
    ...(row as unknown as MallSkuItem),
    salePrice: requiredCount(row.salePrice, 'salePrice'),
    marketPrice: optionalCount(row.marketPrice, 'marketPrice'),
    weightGram: requiredCount(row.weightGram, 'weightGram')
  }
}

export function stockRowOf(row: RawRecord): MallStockItem {
  return {
    ...(row as unknown as MallStockItem),
    availableQty: requiredCount(row.availableQty, 'availableQty'),
    reservedQty: requiredCount(row.reservedQty, 'reservedQty')
  }
}

export function flowRowOf(row: RawRecord): MallStockFlowItem {
  return {
    ...(row as unknown as MallStockFlowItem),
    availableChange: requiredDelta(row.availableChange, 'availableChange'),
    reservedChange: requiredDelta(row.reservedChange, 'reservedChange'),
    availableAfter: requiredCount(row.availableAfter, 'availableAfter'),
    reservedAfter: requiredCount(row.reservedAfter, 'reservedAfter')
  }
}

/** 库存动作结果（R1-P1-2）：首次与重放都是原流水行的冻结值，同样过双形态归一。 */
export function adjustResultOf(raw: unknown): MallStockAdjustResult {
  if (raw === null || raw === undefined || typeof raw !== 'object') {
    throw new Error('商城数据异常：库存动作结果缺失')
  }
  const row = raw as RawRecord
  return {
    ...(row as unknown as MallStockAdjustResult),
    availableChange: requiredDelta(row.availableChange, 'availableChange'),
    availableAfter: requiredCount(row.availableAfter, 'availableAfter'),
    reservedAfter: requiredCount(row.reservedAfter, 'reservedAfter')
  }
}

/**
 * 订单行归一（S2）：三个金额列都是 Long（"2400" 形态），itemKindCount 是 Integer；
 * payStatus 在无支付单时为 null，属正常缺失，不参与数值归一。
 */
export function orderRowOf(row: RawRecord): MallOrderItem {
  return {
    ...(row as unknown as MallOrderItem),
    productAmountFen: requiredCount(row.productAmountFen, 'productAmountFen'),
    deliveryFeeFen: requiredCount(row.deliveryFeeFen, 'deliveryFeeFen'),
    orderAmountFen: requiredCount(row.orderAmountFen, 'orderAmountFen'),
    itemKindCount: optionalCount(row.itemKindCount, 'itemKindCount')
  }
}

/** 订单明细行归一：单价/行金额/重量是 Long，数量是 Integer；全部为下单时刻快照。 */
export function orderLineOf(row: RawRecord): MallOrderLine {
  return {
    ...(row as unknown as MallOrderLine),
    unitPriceFen: requiredCount(row.unitPriceFen, 'unitPriceFen'),
    quantity: requiredCount(row.quantity, 'quantity'),
    itemAmountFen: requiredCount(row.itemAmountFen, 'itemAmountFen'),
    weightGram: requiredCount(row.weightGram, 'weightGram')
  }
}

/** 订单详情归一：概要与明细逐行过双形态归一，任一畸形整单失败（不把错值当成交金额展示）。 */
export function orderDetailOf(raw: unknown): MallOrderDetail {
  if (raw === null || raw === undefined || typeof raw !== 'object') {
    throw new Error('商城数据异常：订单详情缺失')
  }
  const detail = raw as RawRecord
  const summary = detail.summary
  if (summary === null || summary === undefined || typeof summary !== 'object') {
    throw new Error('商城数据异常：订单概要缺失')
  }
  const itemsRaw = Array.isArray(detail.items) ? detail.items : []
  return {
    ...(detail as unknown as MallOrderDetail),
    summary: orderRowOf(summary as RawRecord),
    items: itemsRaw
      .filter((row): row is RawRecord => typeof row === 'object' && row !== null)
      .map(orderLineOf)
  }
}

export function productDetailOf(raw: unknown): MallProductDetail {
  if (raw === null || raw === undefined || typeof raw !== 'object') {
    throw new Error('商城数据异常：商品详情缺失')
  }
  const detail = raw as RawRecord
  const skusRaw = Array.isArray(detail.skus) ? detail.skus : []
  const stocksRaw = Array.isArray(detail.stocks) ? detail.stocks : []
  return {
    ...(detail as unknown as MallProductDetail),
    skus: skusRaw
      .filter((row): row is RawRecord => typeof row === 'object' && row !== null)
      .map(skuRowOf),
    stocks: stocksRaw
      .filter((row): row is RawRecord => typeof row === 'object' && row !== null)
      .map(stockRowOf)
  }
}
