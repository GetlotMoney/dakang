/**
 * 水种套餐 API——水种字典部分（对接 /product/water/*）
 *
 * REQ-073：水种由字典统一维护（启停/排序/默认），出水口与套餐只引用不定义。
 * 套餐接口随水种套餐模块开发追加。
 */
import request from '@/utils/http'

/** 水种 */
export interface WaterTypeItem {
  id: number
  waterName: string
  waterSort: number
  /** 是否默认(1)：1否 2是（全局仅一个默认） */
  defaultFlag: number
  /** 状态(10)：1正常 2禁用 */
  waterStatus: number
  /** 被出水口引用数（删除前置校验参考） */
  outletRefCount?: number
  waterDesc?: string
  createTime?: string
  updateTime?: string
}

/** 水种分页查询参数 */
export interface WaterTypeSearchParams {
  current: number
  size: number
  waterName?: string
  waterStatus?: number
}

/** 水种表单 */
export interface WaterTypeFormData {
  id?: number
  waterName: string
  waterSort: number
  defaultFlag: number
  waterStatus: number
  waterDesc?: string
}

export function fetchWaterTypePage(params: WaterTypeSearchParams) {
  return request.post<{ total: number; list: WaterTypeItem[] }>({
    url: '/product/water/page',
    data: params
  })
}

/** 全部启用水种（出水口/套餐配置下拉） */
export function fetchWaterTypeListEnabled() {
  return request.post<WaterTypeItem[]>({
    url: '/product/water/listEnabled',
    data: {}
  })
}

export function fetchAddWaterType(data: WaterTypeFormData) {
  return request.post<number>({
    url: '/product/water/add',
    data
  })
}

export function fetchUpdateWaterType(data: WaterTypeFormData) {
  return request.post<boolean>({
    url: '/product/water/update',
    data
  })
}

export function fetchDeleteWaterType(id: number) {
  return request.post<boolean>({
    url: '/product/water/delete',
    data: { id }
  })
}

// ==================== 套餐管理（对接 /product/package/*，真实 ws_package） ====================
// 字段口径与 ws_package 表严格对齐（REQ-061：价格/有效期/兑换水量/赠送/状态/单价快照/范围）。
// 金额存"分"，水量存"毫升"；UNIT_PRICE_SNAP 由服务端按 售价÷水量 派生，是退款折算与对账依据。
// SCOPE_JSON 不下发/不直编原文：页面用选择器构造，提交时组装 JSON，服务端 WaterCardScope 终审。

/** 范围类型：all=全场通用，specified=指定水站/设备/出水口 */
export type PackageScopeType = 'all' | 'specified'

/** 套餐列表项（服务端 Long 序列化为字符串；金额/水量经适配层安全转 number） */
export interface PackageItem {
  id: string
  packageName: string
  /** 售价(分) */
  payAmount: number
  /** 兑换水量(毫升)，0=纯余额充值套餐 */
  waterMl: number
  /** 赠送余额(分) */
  bonusAmount: number
  /** 折算单价快照(分/升)，服务端派生 */
  unitPriceSnap: string
  /** 有效期(天)，空=永久 */
  expireDays?: number
  /** 状态(1330)：1在售 2下架 */
  packageStatus: number
  packageRemark?: string
  /** 范围类型；未配置或配置非法时为空 */
  scopeType?: PackageScopeType
  /** 规范化范围 ID（十进制字符串，升序），编辑弹窗回填选择器用 */
  stationIds: string[]
  deviceIds: string[]
  outletIds: string[]
  /** 范围是否合法非空（L2-A6：决定首次购卡可选性） */
  scopeValid: boolean
  /** 范围摘要（服务端派生展示文本） */
  scopeSummary: string
  createTime?: string
  updateTime?: string
}

/** 套餐表单（新增/修改；scopeJson 由页面选择器在提交时组装，空=未配置） */
export interface PackageFormData {
  id?: string
  packageName: string
  payAmount: number
  waterMl: number
  bonusAmount: number
  expireDays?: number
  scopeJson?: string
  /** 仅新增时作为初始状态；修改不改状态，上下架走 fetchShelfPackage */
  packageStatus: number
  packageRemark?: string
}

/** 后端 WsPackageVo 线上结构（Long → string） */
interface RawPackageRow {
  id: string
  packageName: string
  payAmount: string
  waterMl: string
  bonusAmount: string
  unitPriceSnap: string
  expireDays?: number
  packageStatus: number
  packageRemark?: string
  scopeType?: PackageScopeType
  stationIds?: string[]
  deviceIds?: string[]
  outletIds?: string[]
  scopeValid?: boolean
  scopeSummary?: string
  createTime?: string
  updateTime?: string
}

/** Long 字符串 → number：金额/水量在契约上限内（≤1e7 分 / 5e7 毫升），安全整数校验兜底 */
const toSafeAmount = (value: string | number, label: string): number => {
  const num = Number(value)
  if (!Number.isSafeInteger(num) || num < 0) {
    throw new Error(`套餐${label}数值非法：${value}`)
  }
  return num
}

const adaptPackageRow = (row: RawPackageRow): PackageItem => ({
  id: String(row.id),
  packageName: row.packageName,
  payAmount: toSafeAmount(row.payAmount, '售价'),
  waterMl: toSafeAmount(row.waterMl, '水量'),
  bonusAmount: toSafeAmount(row.bonusAmount ?? 0, '赠送余额'),
  unitPriceSnap: row.unitPriceSnap,
  expireDays: row.expireDays ?? undefined,
  packageStatus: row.packageStatus,
  packageRemark: row.packageRemark,
  scopeType: row.scopeType,
  stationIds: row.stationIds ?? [],
  deviceIds: row.deviceIds ?? [],
  outletIds: row.outletIds ?? [],
  scopeValid: row.scopeValid === true,
  scopeSummary: row.scopeSummary || '未配置（首次购卡不可选，充值不加限制）',
  createTime: row.createTime,
  updateTime: row.updateTime
})

/** 套餐分页（权限：登录即可查） */
export async function fetchPackagePage(params: {
  current: number
  size: number
  packageName?: string
  packageStatus?: number
}): Promise<{ total: number; list: PackageItem[] }> {
  const res = await request.post<{ total: string | number; list: RawPackageRow[] }>({
    url: '/product/package/page',
    data: params
  })
  return { total: Number(res.total), list: (res.list ?? []).map(adaptPackageRow) }
}

/** 新增套餐（权限 product:package:add；单价快照由服务端派生，前端不传） */
export function fetchAddPackage(data: PackageFormData) {
  return request.post<string>({
    url: '/product/package/add',
    data
  })
}

/** 修改套餐（权限 product:package:update；不改状态。历史订单按下单快照结算，不受修改影响） */
export function fetchUpdatePackage(data: PackageFormData) {
  return request.post<boolean>({
    url: '/product/package/update',
    data
  })
}

/** 上下架（权限 product:package:shelf；服务端 CAS 状态前置校验，并发冲突明确报错） */
export function fetchShelfPackage(id: string, targetStatus: number) {
  return request.post<boolean>({
    url: '/product/package/shelf',
    data: { id, targetStatus }
  })
}
