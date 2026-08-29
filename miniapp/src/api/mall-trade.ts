import type { EntityId, MoneyFen, PageResult } from './common'
import { ContractError } from './common'
import { normalizeFen, normalizeId, optionalText, specsOf, strictNonNegInt, textOf } from './mall'
import { withRealSession } from './real-session'
import { post } from './request'
import { isDemoMode } from './runtime'

/**
 * 商城交易 API（E2E-09 S2）：购物车、结算预览、下单、订单查询与取消、模拟支付。
 * 形态口径与 mall.ts 一致并复用其归一化原语：Long→字符串与 Integer 裸数字两种形态都收，
 * 畸形值一律 fail-closed 抛 ContractError，绝不回退 Mock。金额恒整数分、ID 恒 string；
 * 下单行由请求显式携带（用户确认的就是结算页那几行），同请求号重放才有得逐字核对。
 */

/** 商城订单状态（字典 1393）。 */
export type MallOrderStatus = 1 | 2 | 3 | 4 | 5 | 6
/** 商城支付状态（字典 1394）。 */
export type MallPayStatus = 1 | 2 | 3 | 4

/**
 * 购物车行 / 结算行（两处同构）。失效行同样下发（purchasable=false + unavailableReason），
 * 不静默丢弃；失效行的 itemAmountFen 恒 0，不计入任何合计。
 */
export interface MallCartLine {
  skuId: EntityId
  /** 失效行（SKU 已被删除）时缺省，不可跳商品详情。 */
  productId?: EntityId
  productName: string
  skuName: string
  specs: Record<string, string>
  coverUrl?: string
  salePriceFen: MoneyFen
  quantity: number
  itemAmountFen: MoneyFen
  purchasable: boolean
  unavailableReason?: string
}

export interface MallCart {
  lines: MallCartLine[]
  /** 有效行合计件数。 */
  totalQuantity: number
  /** 有效行合计金额(分)。 */
  totalAmountFen: MoneyFen
}

/**
 * 结算预览：只读试算，不预占库存。submittable/blockReason 是服务端唯一裁决，前端不自行推算；
 * 预览可下单也不保证创单必成功，创单会再次原子校验。
 */
export interface MallCheckoutPreview {
  lines: MallCartLine[]
  productAmountFen: MoneyFen
  deliveryFeeFen: MoneyFen
  orderAmountFen: MoneyFen
  receiverName: string
  maskedPhone: string
  receiverRegion: string
  receiverAddress: string
  /** 履约仓名称；不可履约时缺省。 */
  warehouseName?: string
  submittable: boolean
  blockReason?: string
}

/** 订单摘要（列表项与详情表头共用）。 */
export interface MallOrderSummary {
  orderId: EntityId
  orderNo: string
  orderStatus: MallOrderStatus
  payStatus?: MallPayStatus
  productAmountFen: MoneyFen
  deliveryFeeFen: MoneyFen
  orderAmountFen: MoneyFen
  itemKindCount: number
  firstProductName?: string
  firstCoverUrl?: string
  warehouseName?: string
  receiverName?: string
  /** 电话恒服务端脱敏，原号不回流前端。 */
  maskedPhone?: string
  payExpireTime?: string
  createTime?: string
  cancelTime?: string
}

/** 订单明细行：全部取自下单时冻结的快照，商品改名改价后历史订单仍显示成交当时的值。 */
export interface MallOrderItemLine {
  /** 原订单明细ID：申请售后时的共键，前端只回传不解释 */
  orderItemId: EntityId
  productId?: EntityId
  skuId: EntityId
  productName: string
  skuName: string
  specs: Record<string, string>
  unitPriceFen: MoneyFen
  quantity: number
  itemAmountFen: MoneyFen
  weightGram: number
}

export interface MallOrderDetail {
  summary: MallOrderSummary
  receiverRegion?: string
  receiverAddress?: string
  cancelReason?: string
  /** 支付来源：1 微信 2 模拟支付。 */
  paySource?: number
  transactionId?: string
  paySuccessTime?: string
  items: MallOrderItemLine[]
}

/** 下单行：SKU + 数量，金额一律服务端按现价重算，请求不带金额。 */
export interface MallOrderLineInput {
  skuId: EntityId
  quantity: number
}

export const mallTradeEndpoints = {
  cartList: '/mini/mall/cart/list',
  cartSave: '/mini/mall/cart/save',
  cartDelete: '/mini/mall/cart/delete',
  checkoutPreview: '/mini/mall/checkout/preview',
  orderCreate: '/mini/mall/order/create',
  orderPage: '/mini/mall/order/page',
  orderDetail: '/mini/mall/order/detail',
  orderCancel: '/mini/mall/order/cancel',
  orderPayStatus: '/mini/mall/order/pay-status',
  paySimPay: '/mini/mall/pay-sim/pay',
} as const

/** 单笔订单的规格上限与单规格件数上限，与服务端校验同值：越界请求在发出前就拦下。 */
export const MALL_LINE_LIMITS = {
  maxKinds: 50,
  maxQuantity: 999,
} as const

// ---------------------------------------------------------------------------
// 归一化
// ---------------------------------------------------------------------------

function rowOf(raw: unknown, code: string, message: string): Record<string, unknown> {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new ContractError(code, message)
  }
  return raw as Record<string, unknown>
}

function rowsOf(raw: unknown): Record<string, unknown>[] {
  return (Array.isArray(raw) ? raw : []).filter(
    (row): row is Record<string, unknown> => typeof row === 'object' && row !== null,
  )
}

/** 件数归一：非法件数=契约破坏。静默降 0 会把「3 件」显示成 0 件而金额照收。 */
function normalizeCount(raw: unknown, field: string, min: number): number {
  const value = strictNonNegInt(raw)
  if (value === undefined || value < min) {
    throw new ContractError('MALL_BAD_QUANTITY', `商品数量数据异常（${field}）`)
  }
  return value
}

function normalizeOptionalId(raw: unknown, field: string): EntityId | undefined {
  return raw === null || raw === undefined ? undefined : normalizeId(raw, field)
}

/** 状态码归一：不在字典取值域内一律拒绝，绝不把未知状态显示成「待支付」。 */
function normalizeStatus<T extends number>(raw: unknown, allowed: readonly T[], field: string): T {
  const value = strictNonNegInt(raw)
  if (value === undefined || !allowed.includes(value as T)) {
    throw new ContractError('MALL_BAD_STATUS', `订单状态数据异常（${field}）`)
  }
  return value as T
}

const ORDER_STATUS_VALUES: readonly MallOrderStatus[] = [1, 2, 3, 4, 5, 6]
const PAY_STATUS_VALUES: readonly MallPayStatus[] = [1, 2, 3, 4]

export function normalizeCartLine(row: Record<string, unknown>): MallCartLine {
  return {
    skuId: normalizeId(row.skuId, 'skuId'),
    productId: normalizeOptionalId(row.productId, 'productId'),
    productName: textOf(row.productName),
    skuName: textOf(row.skuName),
    specs: specsOf(row.specs),
    coverUrl: optionalText(row.coverUrl),
    salePriceFen: normalizeFen(row.salePriceFen, 'salePriceFen'),
    quantity: normalizeCount(row.quantity, 'quantity', 1),
    itemAmountFen: normalizeFen(row.itemAmountFen, 'itemAmountFen'),
    // fail-closed：只有显式 true 才允许结算，缺省/畸形一律按失效行
    purchasable: row.purchasable === true,
    unavailableReason: optionalText(row.unavailableReason),
  }
}

export function normalizeCart(raw: unknown): MallCart {
  const row = rowOf(raw, 'MALL_BAD_CART', '购物车数据异常')
  return {
    lines: rowsOf(row.lines).map(normalizeCartLine),
    totalQuantity: normalizeCount(row.totalQuantity, 'totalQuantity', 0),
    totalAmountFen: normalizeFen(row.totalAmountFen, 'totalAmountFen'),
  }
}

export function normalizeCheckoutPreview(raw: unknown): MallCheckoutPreview {
  const row = rowOf(raw, 'MALL_BAD_CHECKOUT', '结算数据异常')
  return {
    lines: rowsOf(row.lines).map(normalizeCartLine),
    productAmountFen: normalizeFen(row.productAmountFen, 'productAmountFen'),
    deliveryFeeFen: normalizeFen(row.deliveryFeeFen, 'deliveryFeeFen'),
    orderAmountFen: normalizeFen(row.orderAmountFen, 'orderAmountFen'),
    receiverName: textOf(row.receiverName),
    maskedPhone: textOf(row.maskedPhone),
    receiverRegion: textOf(row.receiverRegion),
    receiverAddress: textOf(row.receiverAddress),
    warehouseName: optionalText(row.warehouseName),
    // fail-closed：可否提交只认服务端显式 true，缺省即不可提交
    submittable: row.submittable === true,
    blockReason: optionalText(row.blockReason),
  }
}

export function normalizeOrderSummary(raw: unknown): MallOrderSummary {
  const row = rowOf(raw, 'MALL_BAD_ORDER', '订单数据异常')
  return {
    orderId: normalizeId(row.id, 'id'),
    orderNo: textOf(row.orderNo),
    orderStatus: normalizeStatus(row.orderStatus, ORDER_STATUS_VALUES, 'orderStatus'),
    payStatus: row.payStatus === null || row.payStatus === undefined
      ? undefined
      : normalizeStatus(row.payStatus, PAY_STATUS_VALUES, 'payStatus'),
    productAmountFen: normalizeFen(row.productAmountFen, 'productAmountFen'),
    deliveryFeeFen: normalizeFen(row.deliveryFeeFen, 'deliveryFeeFen'),
    orderAmountFen: normalizeFen(row.orderAmountFen, 'orderAmountFen'),
    itemKindCount: normalizeCount(row.itemKindCount, 'itemKindCount', 0),
    firstProductName: optionalText(row.firstProductName),
    firstCoverUrl: optionalText(row.firstCoverUrl),
    warehouseName: optionalText(row.warehouseName),
    receiverName: optionalText(row.receiverName),
    maskedPhone: optionalText(row.maskedPhone),
    payExpireTime: optionalText(row.payExpireTime),
    createTime: optionalText(row.createTime),
    cancelTime: optionalText(row.cancelTime),
  }
}

function normalizeOrderItemLine(row: Record<string, unknown>): MallOrderItemLine {
  return {
    orderItemId: normalizeId(row.orderItemId, 'orderItemId'),
    productId: normalizeOptionalId(row.productId, 'productId'),
    skuId: normalizeId(row.skuId, 'skuId'),
    productName: textOf(row.productName),
    skuName: textOf(row.skuName),
    specs: specsOf(row.specs),
    unitPriceFen: normalizeFen(row.unitPriceFen, 'unitPriceFen'),
    quantity: normalizeCount(row.quantity, 'quantity', 1),
    itemAmountFen: normalizeFen(row.itemAmountFen, 'itemAmountFen'),
    weightGram: normalizeFen(row.weightGram, 'weightGram'),
  }
}

export function normalizeOrderDetail(raw: unknown): MallOrderDetail {
  const row = rowOf(raw, 'MALL_BAD_ORDER', '订单数据异常')
  return {
    summary: normalizeOrderSummary(row.summary),
    receiverRegion: optionalText(row.receiverRegion),
    receiverAddress: optionalText(row.receiverAddress),
    cancelReason: optionalText(row.cancelReason),
    paySource: strictNonNegInt(row.paySource),
    transactionId: optionalText(row.transactionId),
    paySuccessTime: optionalText(row.paySuccessTime),
    items: rowsOf(row.items).map(normalizeOrderItemLine),
  }
}

/** 分页归一：total 是 Long→字符串，转 number 贴合 PageResult；畸形总数不许当 0 蒙混。 */
export function normalizeOrderPage(raw: unknown): PageResult<MallOrderSummary> {
  const row = rowOf(raw, 'MALL_BAD_ORDER', '订单数据异常')
  const total = strictNonNegInt(row.total)
  if (total === undefined) {
    throw new ContractError('MALL_BAD_TOTAL', '订单数据异常（total）')
  }
  return { list: rowsOf(row.list).map(normalizeOrderSummary), total }
}

// ---------------------------------------------------------------------------
// 请求侧
// ---------------------------------------------------------------------------

/**
 * 创单幂等键：必须是规范小写带连字符 UUID（服务端 MallOrderNo 据此派生订单号，别的形状被拒）；
 * 不用 crypto.randomUUID()——小程序运行时没有该 API。
 */
export function createMallRequestId(): string {
  const bytes = new Uint8Array(16)
  for (let i = 0; i < 16; i++) {
    bytes[i] = Math.floor(Math.random() * 256)
  }
  // 版本位 4 与变体位 10xx，保证是规范 v4，否则服务端正则会拒
  bytes[6] = (bytes[6] & 0x0F) | 0x40
  bytes[8] = (bytes[8] & 0x3F) | 0x80
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** 出参行校验：ID 必须是正十进制、数量在 1~999，越界在发请求前就拒绝。 */
function requireValidLines(lines: MallOrderLineInput[]): MallOrderLineInput[] {
  if (lines.length === 0) {
    throw new ContractError('MALL_LINES_EMPTY', '请选择要购买的商品')
  }
  if (lines.length > MALL_LINE_LIMITS.maxKinds) {
    throw new ContractError('MALL_LINES_TOO_MANY', `一笔订单最多 ${MALL_LINE_LIMITS.maxKinds} 个规格`)
  }
  const seen = new Set<string>()
  for (const line of lines) {
    const skuId = String(line.skuId)
    if (!/^[1-9]\d*$/.test(skuId)) {
      throw new ContractError('MALL_LINE_INVALID', '商品规格信息有误，请重新选择')
    }
    if (!Number.isSafeInteger(line.quantity)
      || line.quantity < 1
      || line.quantity > MALL_LINE_LIMITS.maxQuantity) {
      throw new ContractError('MALL_LINE_INVALID', `每个规格最多 ${MALL_LINE_LIMITS.maxQuantity} 件`)
    }
    if (seen.has(skuId)) {
      throw new ContractError('MALL_LINE_DUPLICATED', '同一规格重复，请合并数量后重试')
    }
    seen.add(skuId)
  }
  return lines
}

/**
 * 下单行的页面间编码（`skuId:数量` 逗号分隔）：编码进页面参数而非内存草稿，页面被系统回收重建后参数仍在。
 */
export function encodeCheckoutLines(lines: MallOrderLineInput[]): string {
  return requireValidLines(lines).map(line => `${line.skuId}:${line.quantity}`).join(',')
}

/** 解码结算行；任何畸形片段（含空片段）整次拒绝，绝不带半份行进结算页。 */
export function decodeCheckoutLines(raw: string): MallOrderLineInput[] {
  let decoded = ''
  try {
    // 路由合同会 encodeURIComponent；微信 onLoad 收到的值可能仍保留 %3A/%2C，必须在此统一解一次
    decoded = decodeURIComponent(raw ?? '')
  }
  catch {
    throw new ContractError('MALL_LINE_INVALID', '商品规格信息有误，请重新选择')
  }
  const lines: MallOrderLineInput[] = []
  for (const chunk of decoded.split(',')) {
    const matched = /^([1-9]\d*):([1-9]\d*)$/.exec(chunk)
    if (!matched) {
      throw new ContractError('MALL_LINE_INVALID', '商品规格信息有误，请重新选择')
    }
    lines.push({ skuId: matched[1], quantity: Number(matched[2]) })
  }
  return requireValidLines(lines)
}

function linesBody(lines: MallOrderLineInput[]): Record<string, unknown>[] {
  return requireValidLines(lines).map(line => ({
    skuId: String(line.skuId),
    quantity: line.quantity,
  }))
}

// ---------------------------------------------------------------------------
// 适配器
// ---------------------------------------------------------------------------

async function cartList(): Promise<MallCart> {
  return withRealSession(async () => normalizeCart(await post(mallTradeEndpoints.cartList, {})))
}

/** increment=true 在现有数量上累加（商品详情页加购）；false 覆盖为该数量（购物车页改量）。 */
async function cartSave(input: {
  skuId: EntityId
  quantity: number
  increment: boolean
}): Promise<MallCart> {
  requireValidLines([{ skuId: input.skuId, quantity: input.quantity }])
  return withRealSession(async () => normalizeCart(await post(mallTradeEndpoints.cartSave, {
    skuId: String(input.skuId),
    quantity: input.quantity,
    increment: input.increment,
  })))
}

async function cartDelete(skuId: EntityId): Promise<MallCart> {
  return withRealSession(async () => normalizeCart(
    await post(mallTradeEndpoints.cartDelete, { skuId: String(skuId) }),
  ))
}

async function checkoutPreview(input: {
  addressId: EntityId
  lines: MallOrderLineInput[]
}): Promise<MallCheckoutPreview> {
  const body = { addressId: String(input.addressId), lines: linesBody(input.lines) }
  return withRealSession(async () => normalizeCheckoutPreview(
    await post(mallTradeEndpoints.checkoutPreview, body),
  ))
}

/** 创建订单。requestId 由结算页会话持有并在重试间保持不变，重试换号会下出第二张单。 */
async function createOrder(input: {
  addressId: EntityId
  lines: MallOrderLineInput[]
  requestId: string
}): Promise<MallOrderSummary> {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(input.requestId)) {
    throw new ContractError('MALL_REQUEST_ID_INVALID', '下单信息有误，请返回重试')
  }
  const body = {
    addressId: String(input.addressId),
    lines: linesBody(input.lines),
    requestId: input.requestId,
  }
  return withRealSession(async () => normalizeOrderSummary(
    await post(mallTradeEndpoints.orderCreate, body),
  ))
}

async function listOrders(query: {
  current?: number
  size?: number
  orderStatus?: MallOrderStatus
} = {}): Promise<PageResult<MallOrderSummary>> {
  return withRealSession(async () => normalizeOrderPage(await post(mallTradeEndpoints.orderPage, {
    current: query.current ?? 1,
    size: query.size ?? 10,
    orderStatus: query.orderStatus,
  })))
}

async function orderDetail(orderNo: string): Promise<MallOrderDetail> {
  return withRealSession(async () => normalizeOrderDetail(
    await post(mallTradeEndpoints.orderDetail, { orderNo }),
  ))
}

async function cancelOrder(orderNo: string, cancelReason?: string): Promise<MallOrderSummary> {
  return withRealSession(async () => normalizeOrderSummary(
    await post(mallTradeEndpoints.orderCancel, { orderNo, cancelReason }),
  ))
}

async function payStatus(orderNo: string): Promise<MallOrderDetail> {
  return withRealSession(async () => normalizeOrderDetail(
    await post(mallTradeEndpoints.orderPayStatus, { orderNo }),
  ))
}

/** 触发一次模拟支付；真实微信支付接入后这里换成 requestPayment。 */
async function simulatePay(orderNo: string): Promise<MallOrderDetail> {
  if (!isDemoMode()) {
    throw new ContractError('MALL_PAY_SIM_DISABLED', '商城微信支付尚未开通')
  }
  return withRealSession(async () => normalizeOrderDetail(
    await post(mallTradeEndpoints.paySimPay, { orderNo }),
  ))
}

export const mallTradeApi = {
  cartList,
  cartSave,
  cartDelete,
  checkoutPreview,
  createOrder,
  listOrders,
  orderDetail,
  cancelOrder,
  payStatus,
  simulatePay,
}
