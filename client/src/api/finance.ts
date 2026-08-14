import request from '@/utils/http'
import { fetchOrderPage } from '@/api/order'

/**
 * 财务分账与对账（E2E-08 包E）。/finance 前缀已在 vite.config.ts 与 nginx.conf 双处登记。
 * Pay-Sim 环境下分账为账务计提口径（记账不动钱），页面措辞须与之一致。
 *
 * 本文件同时是 PC 财务治理面的只读聚合层。队列归组与对账业务键解析下沉在
 * finance-governance.ts（纯策略、可单测），这里只做请求与响应归一化；两处都只有一份定义，
 * 页面不得各写一份（两份分组=两份治理口径）。
 * 全部为读；仓库无「人工资金调整」契约，本层不提供也不得新增任何资金写入口。
 */

export * from './finance-governance'

export interface SplitRecordItem {
  id: string
  orderId: string
  receiverType: number
  receiverUserId?: string
  splitAmount: number
  splitRateSnap: string
  splitStatus: number
  splitTime?: string
  splitRemark?: string
  /** 已冲减金额(分)（D-420）：被水费退款冲减的份额；0=未冲减 */
  reversedAmount?: number
  /** 触发冲减的退款单ID；未被冲减时缺省 */
  refundId?: string
  createTime: string
}

/** 自动补货规则（S2 只读：客服追踪；启停与取消是用户自助动作） */
export interface AutoRuleItem {
  id: string
  userId: string
  cardId: string
  stationId: string
  waterTypeId: string
  containerSpec: string
  deliveryCount: number
  planReturnCount: number
  receiveAddress: string
  receivePhone: string
  intervalDays: number
  anchorTime: string
  ruleStatus: number
  createTime: string
}

export interface AutoRulePageParams {
  current: number
  size: number
  userId?: string
  ruleStatus?: number
}

export interface ReconcileTaskItem {
  id: string
  bizDate: string
  taskStatus: number
  checkTotal: number
  diffTotal: number
  updateTime: string
}

export interface ReconcileDiffItem {
  id: string
  bizDate: string
  diffType: number
  checkDimension: string
  bizKey: string
  expectedVal?: string
  actualVal?: string
  diffRemark?: string
}

export interface SplitConfigItem {
  id: string
  productLine: number
  receiverType: number
  splitRate: number
  effectTime: string
  configRemark?: string
}

export interface SplitPageParams {
  current?: number
  size?: number
  orderId?: string | number
  receiverType?: number
  splitStatus?: number
}

export interface ReconcilePageParams {
  current?: number
  size?: number
  bizDate?: string
  diffType?: number
}

export interface SplitConfigPageParams {
  current?: number
  size?: number
  productLine?: number
}

export interface SplitConfigCreateParams {
  productLine: number
  receiverType: number
  /** 整数万分比（页面用 percentToBp 换算，7000=70%） */
  splitRate: number
  /** 缺省=服务端当下；不允许过去时点 */
  effectTime?: string
  remark?: string
}

type PageResult<T> = { total: number; list: T[] }

/** ID 一律保持字符串：分账行的订单ID 与退款单ID 都是 Long，数值化后超 2^53 会指向错行。 */
const toIdStr = (v: unknown): string | undefined => {
  if (v === null || v === undefined || v === '') return undefined
  return String(v)
}

/** 金额恒为整数分。Long 经序列化为字符串，字符串参与比较会按字典序误判正负。 */
const toFen = (v: unknown): number | undefined => {
  if (v === null || v === undefined || v === '') return undefined
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

/**
 * 分账行归一化：ID 保 string、金额转 number。
 * 页面据 reversedAmount 判定是否展示冲减证据，字符串在此处会静默判错。
 */
function normalizeSplitRecord(raw: Record<string, any>): SplitRecordItem {
  return {
    id: toIdStr(raw.id) ?? '',
    orderId: toIdStr(raw.orderId) ?? '',
    receiverType: Number(raw.receiverType),
    receiverUserId: toIdStr(raw.receiverUserId),
    splitAmount: toFen(raw.splitAmount) ?? 0,
    splitRateSnap: typeof raw.splitRateSnap === 'string' ? raw.splitRateSnap : '',
    splitStatus: Number(raw.splitStatus),
    splitTime: typeof raw.splitTime === 'string' ? raw.splitTime : undefined,
    splitRemark: typeof raw.splitRemark === 'string' ? raw.splitRemark : undefined,
    reversedAmount: toFen(raw.reversedAmount),
    refundId: toIdStr(raw.refundId),
    createTime: typeof raw.createTime === 'string' ? raw.createTime : ''
  }
}

export async function fetchSplitPage(data: SplitPageParams): Promise<PageResult<SplitRecordItem>> {
  const res = await request.post<{ list?: Record<string, any>[]; total?: unknown }>({
    url: '/finance/split/page',
    data
  })
  const rawList = Array.isArray(res?.list) ? res.list : []
  return { list: rawList.map(normalizeSplitRecord), total: toFen(res?.total) ?? rawList.length }
}

/** 自动补货规则分页（只读） */
export function fetchAutoRulePage(data: AutoRulePageParams) {
  return request.post<PageResult<AutoRuleItem>>({ url: '/order/autoRule/page', data })
}

/** 按水种用量统计（S5 只读；生产量无权威数据源，页面显式标注未提供） */
export interface WaterStatsRow {
  waterTypeName: string
  planMl: number
  actualMl: number
  shortfallMl: number
  abnormalCount: number
  deliveryBuckets: number
  deliveryMl: number
  resendBuckets: number
  resendMl: number
}

export function fetchWaterStatsSummary(data: {
  startTime: string
  endTime: string
  stationId?: string
  waterTypeName?: string
}) {
  return request.post<WaterStatsRow[]>({ url: '/order/waterStats/summary', data })
}

export function fetchReconcileTaskPage(data: ReconcilePageParams) {
  return request.post<PageResult<ReconcileTaskItem>>({ url: '/finance/reconcile/taskPage', data })
}

export function fetchReconcileDiffPage(data: ReconcilePageParams) {
  return request.post<PageResult<ReconcileDiffItem>>({ url: '/finance/reconcile/diffPage', data })
}

export function fetchReconcileRun(bizDate: string) {
  return request.post<ReconcileTaskItem>({ url: '/finance/reconcile/run', data: { bizDate } })
}

export function fetchSplitConfigPage(data: SplitConfigPageParams) {
  return request.post<PageResult<SplitConfigItem>>({ url: '/finance/config/page', data })
}

export function fetchSplitConfigCreate(data: SplitConfigCreateParams) {
  return request.post<string>({ url: '/finance/config/create', data })
}

/**
 * 订单号 → 订单ID。分账明细按订单ID 索引，而运营手里只有订单号。
 * 订单分页按订单号做包含匹配，因此必须逐字比对后再取 ID：子串命中会把邻单的分账行当成本单的。
 * 找不到时返回 undefined，调用方据此如实置空列表，绝不能回落成不带条件的全量查询。
 */
export async function resolveOrderIdByNo(orderNo: string): Promise<string | undefined> {
  const keyword = orderNo.trim()
  if (!keyword) return undefined
  const result = await fetchOrderPage({ current: 1, size: 20, orderNo: keyword })
  return result.list.find((item) => item.orderNo === keyword)?.id
}

// ==================== 分润 V2 整版计划与归属载体（D-428 六方口径） ====================

export interface SplitPlanItemRow {
  id: string
  planId: string
  /** WATER_SALE 售水 / DELIVERY_FEE 配送费 */
  productLine: string
  roleCode: string
  regionLevel: string
  /** 万分比；级差模式为该层累计上限 */
  rateBp: number
  rateMode: string
}

export interface SplitPlanRow {
  id: string
  planVersion: string
  effectTime: string
  /** 1草稿 2生效 3停用 */
  planStatus: number
  planRemark?: string
}

export interface SplitPlanCreateParams {
  /** 全部整数万分比；六个数一次给齐（可为 0，不可缺省） */
  waterOwnerBp: number
  waterReferrerBp: number
  regionProvinceCumBp: number
  regionCityCumBp: number
  regionCountyCumBp: number
  deliveryCourierBp: number
  effectTime?: string
  remark?: string
}

export interface OwnerReferrerRow {
  id: string
  ownerUserId: string
  referrerUserId: string
  bindSource: string
  bindTime: string
  referrerRemark?: string
}

export interface OwnerAttributionRow {
  id: string
  ownerUserId: string
  attributionSource: string
  provinceAgentUserId?: string
  cityAgentUserId?: string
  countyAgentUserId?: string
  bindTime: string
  attributionRemark?: string
}

/** Long ID 恒 string（防 2^53 精度截断）；后端 Jackson 对 Long 入参接受字符串形态。 */
const idText = (value: unknown): string => (value == null ? '' : String(value))

const normalizePlanRow = (raw: Record<string, unknown>): SplitPlanRow => ({
  id: idText(raw.id),
  planVersion: String(raw.planVersion ?? ''),
  effectTime: String(raw.effectTime ?? ''),
  planStatus: Number(raw.planStatus ?? 0),
  planRemark: raw.planRemark == null ? undefined : String(raw.planRemark)
})

export async function fetchSplitPlanPage(data: { current?: number; size?: number }) {
  const result = await request.post<PageResult<Record<string, unknown>>>({
    url: '/finance/plan/page',
    data
  })
  return {
    total: result?.total ?? 0,
    list: (result?.list ?? []).map(normalizePlanRow)
  }
}

export async function fetchSplitPlanItems(planId: string) {
  const rows = await request.post<Record<string, unknown>[]>({
    url: '/finance/plan/items',
    data: { planId }
  })
  return (rows ?? []).map(
    (raw): SplitPlanItemRow => ({
      id: idText(raw.id),
      planId: idText(raw.planId),
      productLine: String(raw.productLine ?? ''),
      roleCode: String(raw.roleCode ?? ''),
      regionLevel: String(raw.regionLevel ?? ''),
      rateBp: Number(raw.rateBp ?? 0),
      rateMode: String(raw.rateMode ?? '')
    })
  )
}

export function fetchSplitPlanCreate(data: SplitPlanCreateParams) {
  return request.post<string>({ url: '/finance/plan/create', data })
}

const normalizeReferrerRow = (raw: Record<string, unknown>): OwnerReferrerRow => ({
  id: idText(raw.id),
  ownerUserId: idText(raw.ownerUserId),
  referrerUserId: idText(raw.referrerUserId),
  bindSource: String(raw.bindSource ?? ''),
  bindTime: String(raw.bindTime ?? ''),
  referrerRemark: raw.referrerRemark == null ? undefined : String(raw.referrerRemark)
})

const normalizeAttributionRow = (raw: Record<string, unknown>): OwnerAttributionRow => ({
  id: idText(raw.id),
  ownerUserId: idText(raw.ownerUserId),
  attributionSource: String(raw.attributionSource ?? ''),
  provinceAgentUserId:
    raw.provinceAgentUserId == null ? undefined : idText(raw.provinceAgentUserId),
  cityAgentUserId: raw.cityAgentUserId == null ? undefined : idText(raw.cityAgentUserId),
  countyAgentUserId: raw.countyAgentUserId == null ? undefined : idText(raw.countyAgentUserId),
  bindTime: String(raw.bindTime ?? ''),
  attributionRemark: raw.attributionRemark == null ? undefined : String(raw.attributionRemark)
})

export async function fetchOwnerReferrerPage(data: {
  current?: number
  size?: number
  ownerUserId?: string
  referrerUserId?: string
}) {
  const result = await request.post<PageResult<Record<string, unknown>>>({
    url: '/finance/attribution/referrerPage',
    data
  })
  return { total: result?.total ?? 0, list: (result?.list ?? []).map(normalizeReferrerRow) }
}

export async function fetchOwnerAttributionPage(data: {
  current?: number
  size?: number
  ownerUserId?: string
}) {
  const result = await request.post<PageResult<Record<string, unknown>>>({
    url: '/finance/attribution/chainPage',
    data
  })
  return { total: result?.total ?? 0, list: (result?.list ?? []).map(normalizeAttributionRow) }
}

export function fetchOwnerReferrerCreate(data: {
  ownerUserId: string
  referrerUserId: string
  remark?: string
}) {
  return request.post<string>({ url: '/finance/attribution/referrerCreate', data })
}

export function fetchOwnerAttributionCreate(data: {
  ownerUserId: string
  attributionSource: string
  provinceAgentUserId?: string
  cityAgentUserId?: string
  countyAgentUserId?: string
  remark?: string
}) {
  return request.post<string>({ url: '/finance/attribution/chainCreate', data })
}
