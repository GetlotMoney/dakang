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
  createTime: string
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
