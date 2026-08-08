import request from '@/utils/http'

/**
 * 财务分账与对账（E2E-08 包E）。/finance 前缀已在 vite.config.ts 与 nginx.conf 双处登记。
 * Pay-Sim 环境下分账为账务计提口径（记账不动钱），页面措辞须与之一致。
 */

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

export function fetchSplitPage(data: SplitPageParams) {
  return request.post<PageResult<SplitRecordItem>>({ url: '/finance/split/page', data })
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
