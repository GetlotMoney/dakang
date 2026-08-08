import type { CapabilityCode } from '@/api/account'
import type { TabbarName } from '@/tabbar/config'
import { ContractError } from '@/api/common'

export type RouteId
  = | 'C01'
    | 'C02'
    | 'C03'
    | 'U01'
    | 'U02'
    | 'U03'
    | 'U04'
    | 'U05'
    | 'U06'
    | 'U07'
    | 'U08'
    | 'U09'
    | 'U10'
    | 'U11'
    | 'U12'
    | 'U13'
    | 'U14'
    | 'U15'
    | 'U16'
    | 'D01'
    | 'D02'
    | 'D03'
    | 'D04'
    | 'D05'
    | 'O01'
    | 'O02'
    | 'O03'
    | 'O04'
    | 'O05'
    | 'O06'

export type RouteGroup = 'common' | 'user' | 'courier' | 'owner'

export interface RouteParamContract {
  name: string
  required: boolean
  values?: readonly string[]
}

export interface AppRouteContract {
  id: RouteId
  path: string
  title: string
  group: RouteGroup
  tab?: TabbarName
  requiredCapability?: CapabilityCode
  params: readonly RouteParamContract[]
  defaultBackTo: RouteId
}

function required(name: string): RouteParamContract {
  return { name, required: true }
}

function optional(name: string, values?: readonly string[]): RouteParamContract {
  return { name, required: false, values }
}

export const appRoutes = [
  { id: 'C01', path: '/pages/entry/index', title: '统一入口', group: 'common', params: [], defaultBackTo: 'U01' },
  { id: 'C02', path: '/pages/message/index', title: '消息中心', group: 'common', requiredCapability: 'USER_BASE', params: [optional('domain', ['water', 'card', 'delivery', 'owner', 'system'])], defaultBackTo: 'U01' },
  { id: 'C03', path: '/pages/message/detail', title: '消息详情', group: 'common', requiredCapability: 'USER_BASE', params: [required('messageId')], defaultBackTo: 'C02' },
  { id: 'U01', path: '/pages/user/home/index', title: '首页', group: 'user', tab: 'home', requiredCapability: 'USER_BASE', params: [], defaultBackTo: 'U01' },
  { id: 'U02', path: '/pages/user/order/index', title: '订单', group: 'user', tab: 'order', requiredCapability: 'USER_BASE', params: [], defaultBackTo: 'U01' },
  { id: 'U03', path: '/pages/user/profile/index', title: '我的', group: 'user', tab: 'profile', requiredCapability: 'USER_BASE', params: [], defaultBackTo: 'U01' },
  { id: 'U04', path: '/pages/user/water/confirm', title: '取水确认', group: 'user', requiredCapability: 'USER_BASE', params: [required('scanSessionId')], defaultBackTo: 'U01' },
  { id: 'U05', path: '/pages/user/water/progress', title: '取水进度', group: 'user', requiredCapability: 'USER_BASE', params: [required('orderNo')], defaultBackTo: 'U01' },
  { id: 'U06', path: '/pages/user/order/detail', title: '订单详情', group: 'user', requiredCapability: 'USER_BASE', params: [required('orderNo'), optional('focus', ['command', 'delivery', 'appeal']), optional('source', ['local-mock'])], defaultBackTo: 'U02' },
  { id: 'U07', path: '/pages/user/station/index', title: '附近水站', group: 'user', requiredCapability: 'USER_BASE', params: [optional('stationId'), optional('selectMode', ['delivery'])], defaultBackTo: 'U01' },
  { id: 'U08', path: '/pages/user/delivery/create', title: '配送下单', group: 'user', requiredCapability: 'USER_BASE', params: [optional('stationId'), optional('addressId')], defaultBackTo: 'U01' },
  { id: 'U09', path: '/pages/user/appeal/create', title: '配送申诉', group: 'user', requiredCapability: 'USER_BASE', params: [required('orderNo'), required('taskNo')], defaultBackTo: 'U06' },
  { id: 'U10', path: '/pages/user/recharge/index', title: '充值', group: 'user', requiredCapability: 'USER_BASE', params: [optional('cardId'), optional('packageId')], defaultBackTo: 'U03' },
  { id: 'U11', path: '/pages/user/card/detail', title: '水卡详情', group: 'user', requiredCapability: 'USER_BASE', params: [required('cardId')], defaultBackTo: 'U03' },
  { id: 'U12', path: '/pages/user/card/member-form', title: '成员授权', group: 'user', requiredCapability: 'USER_BASE', params: [required('cardId'), optional('memberId')], defaultBackTo: 'U11' },
  { id: 'U13', path: '/pages/user/family/index', title: '家庭资料', group: 'user', requiredCapability: 'USER_BASE', params: [optional('tab', ['profile', 'reward'])], defaultBackTo: 'U03' },
  { id: 'U14', path: '/pages/user/address/index', title: '水配送地址', group: 'user', requiredCapability: 'USER_BASE', params: [optional('returnTo', ['delivery'])], defaultBackTo: 'U03' },
  { id: 'U15', path: '/pages/user/address/edit', title: '编辑水配送地址', group: 'user', requiredCapability: 'USER_BASE', params: [optional('addressId'), optional('returnTo', ['delivery'])], defaultBackTo: 'U14' },
  { id: 'U16', path: '/pages/user/delivery/auto-rules', title: '自动补货规则', group: 'user', requiredCapability: 'USER_BASE', params: [], defaultBackTo: 'U03' },
  { id: 'D01', path: '/pages/courier/task/index', title: '配送任务中心', group: 'courier', requiredCapability: 'COURIER_WORK', params: [optional('view', ['available', 'active', 'history'])], defaultBackTo: 'U01' },
  { id: 'D02', path: '/pages/courier/admission/index', title: '配送准入', group: 'courier', requiredCapability: 'COURIER_APPLY', params: [], defaultBackTo: 'U03' },
  { id: 'D03', path: '/pages/courier/task/detail', title: '配送任务详情', group: 'courier', requiredCapability: 'COURIER_WORK', params: [required('taskNo'), optional('focus', ['appeal'])], defaultBackTo: 'D01' },
  { id: 'D04', path: '/pages/courier/task/sign', title: '三照签收', group: 'courier', requiredCapability: 'COURIER_WORK', params: [required('taskNo')], defaultBackTo: 'D03' },
  { id: 'D05', path: '/pages/courier/task/exception', title: '配送异常', group: 'courier', requiredCapability: 'COURIER_WORK', params: [required('taskNo')], defaultBackTo: 'D03' },
  { id: 'O01', path: '/pages/owner/overview/index', title: '经营概览', group: 'owner', requiredCapability: 'OWNER_VIEW', params: [], defaultBackTo: 'U01' },
  { id: 'O02', path: '/pages/owner/device/index', title: '机主设备', group: 'owner', requiredCapability: 'OWNER_VIEW', params: [optional('stationId'), optional('status', ['ONLINE', 'OFFLINE', 'IDLE', 'DISPENSING', 'FAULT', 'MAINTENANCE', 'LOCKED'])], defaultBackTo: 'O01' },
  { id: 'O03', path: '/pages/owner/device/detail', title: '设备详情', group: 'owner', requiredCapability: 'OWNER_VIEW', params: [required('deviceNo')], defaultBackTo: 'O02' },
  { id: 'O04', path: '/pages/owner/transaction/index', title: '交易快照', group: 'owner', requiredCapability: 'OWNER_VIEW', params: [optional('period', ['7d']), optional('deviceNo')], defaultBackTo: 'O01' },
  { id: 'O05', path: '/pages/owner/service/index', title: '报修与配件', group: 'owner', requiredCapability: 'OWNER_SERVICE', params: [optional('serviceType', ['REPAIR', 'PART']), optional('deviceNo'), optional('requestId')], defaultBackTo: 'O01' },
  // 收益钱包按 USER_BASE 开放：分账收款方不止机主（配送线 30% 归配送员，推荐人/区域服务商
  // 为预留收款方），绑 OWNER_VIEW 会让拿了分润的配送员看不到自己的钱（主环境实点抓获）。
  // 后端 /mini/owner/wallet 本就只校验登录会话并按会话 userId 过滤，此处与之对齐；
  // 无分润账户呈现零余额空态，不泄露他人数据。回退目标取「我的」而非机主概览——
  // 非机主进不去 O01，回退到进不去的页面等于把用户困在钱包页。
  { id: 'O06', path: '/pages/owner/wallet/index', title: '收益钱包', group: 'owner', requiredCapability: 'USER_BASE', params: [], defaultBackTo: 'U03' },
] as const satisfies readonly AppRouteContract[]

export interface ParsedRouteLocation {
  path: string
  params: Record<string, string>
}

export interface RouteValidationResult {
  valid: boolean
  message?: string
}

function normalizePath(path: string) {
  const value = path.trim().split('?')[0]
  return value.startsWith('/') ? value : `/${value}`
}

export function parseRouteUrl(url: string): ParsedRouteLocation {
  const [rawPath, rawQuery = ''] = url.split('?')
  const params: Record<string, string> = {}
  for (const item of rawQuery.split('&')) {
    if (!item) {
      continue
    }
    const [rawName, rawValue = ''] = item.split('=')
    params[decodeURIComponent(rawName)] = decodeURIComponent(rawValue)
  }
  return { path: normalizePath(rawPath), params }
}

export function findRouteByPath(path: string): AppRouteContract | undefined {
  const normalized = normalizePath(path)
  return appRoutes.find(route => route.path === normalized)
}

export function findRouteById(routeId: RouteId): AppRouteContract {
  return appRoutes.find(route => route.id === routeId) as AppRouteContract
}

export function validateRouteParams(
  route: AppRouteContract,
  params: Record<string, string>,
): RouteValidationResult {
  const knownNames = new Set(route.params.map(item => item.name))
  const unknownName = Object.keys(params).find(name => !knownNames.has(name))
  if (unknownName) {
    return { valid: false, message: '页面参数不合法' }
  }

  for (const contract of route.params) {
    const value = params[contract.name]
    if (contract.required && !value) {
      return { valid: false, message: '页面参数不完整' }
    }
    if (value && contract.values && !contract.values.includes(value)) {
      return { valid: false, message: '页面参数不合法' }
    }
  }
  return { valid: true }
}

export function buildRouteUrl(
  routeId: RouteId,
  params: Record<string, string | number | undefined> = {},
) {
  const route = findRouteById(routeId)
  const normalizedParams = Object.fromEntries(
    Object.entries(params)
      .filter((entry): entry is [string, string | number] => entry[1] !== undefined)
      .map(([name, value]) => [name, String(value)]),
  )
  const validation = validateRouteParams(route, normalizedParams)
  if (!validation.valid) {
    throw new ContractError('ROUTE_PARAMS_INVALID', validation.message ?? '页面参数不合法')
  }
  const query = Object.entries(normalizedParams)
    .map(([name, value]) => `${encodeURIComponent(name)}=${encodeURIComponent(value)}`)
    .join('&')
  return query ? `${route.path}?${query}` : route.path
}
