/**
 * 运营总览 API（2026-08-02 接真）。
 *
 * 5+1 之「运营总览」——最小只读看板（订单/设备/命令/ACK/审计）。
 * 红线（REQ-067）：指标口径一律来自后端数据库聚合（/ws/dashboard/overview），
 * 前端不做任何反算或凑数；口径定义固化在服务端 DashboardOverviewVo 注释。
 * 指令看板复用真实指令分页（/device/command/page），审计流复用领域事件
 * 白名单分页（/api/domainEvent/whitelistPage）——白名单是安全边界，总览不得绕过。
 */

import request from '@/utils/http'

// ============ 类型定义 ============

/** 核心指标 + 待办计数（服务端聚合快照的前端投影） */
export interface OverviewStats {
  /** 今日取水订单总数 */
  todayWaterOrders: number
  /** 今日取水分状态：完成/出水中/异常/取消 */
  todayWaterDone: number
  todayWaterDispensing: number
  todayWaterException: number
  todayWaterCanceled: number
  /** 今日营收(分)：取水+配送已支付族，充值不计（口径见服务端 VO 注释） */
  todayRevenue: number
  /** 在线设备/设备总数 */
  onlineDevices: number
  totalDevices: number
  /** 指令 24h 三态与成功率；无终态样本时 rate 为 null（页面显示占位，不给假值） */
  cmd24hSuccess: number
  cmd24hFail: number
  cmd24hTimeout: number
  cmdSuccessRate: number | null
  /** 待处理事项 */
  pendingExceptionOrders: number
  pendingAppeals: number
  pendingCouriers: number
  pendingDeliveries: number
}

/** 近 7 日订单趋势点（date 为 MM-DD 展示格式） */
export interface OrderTrendPoint {
  date: string
  waterOrders: number
  rechargeOrders: number
  deliveryOrders: number
}

/** 指令回执监控行（复用真实指令分页；类型标签由页面按字典 1320 渲染） */
export interface CommandMonitorRow {
  cmdNo: string
  deviceNo: string
  /** 指令类型(1320) */
  cmdType?: number
  /** 指令状态(1321) */
  cmdStatus: number
  sentTime?: string
  ackTime?: string
  finishTime?: string
  failReason?: string
}

/** 审计事件行（ws_domain_event 白名单投影；标签由页面按字典 1363/1364 渲染） */
export interface AuditEventRow {
  id: string
  /** 领域事件类型(1363) */
  eventType?: number
  eventKey: string
  /** 触发端口(1364)：1公司后台 2用户端 … 7设备 */
  actorPortal?: number
  /** 载荷摘要（原文截断，完整内容在运维事件页） */
  detail: string
  time?: string
}

// ============ 归一化 ============

const toNum = (v: unknown): number => {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

const toOptNum = (v: unknown): number | undefined => {
  if (v === null || v === undefined || v === '') return undefined
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

const toStr = (v: unknown): string => (v === null || v === undefined ? '' : String(v))

/** 载荷摘要：审计流每行一句话，超长截断；完整报文去运维事件页看 */
const PAYLOAD_PREVIEW_LIMIT = 90
const EVENT_SUMMARY_KEYS = ['summary', 'detail', 'message', 'new', 'reason', 'action', 'result']
const INTERNAL_EVENT_FIELD = /\b(?:node|orderNo|taskNo|cmdNo|deviceNo|requestId|appealId)\s*[:=]/i

const clipPayloadPreview = (text: string): string =>
  text.length > PAYLOAD_PREVIEW_LIMIT ? `${text.slice(0, PAYLOAD_PREVIEW_LIMIT)}…` : text

/** 领域事件中的关联键只用于追溯，首页保留业务结论，避免把内部字段暴露给运营人员。 */
const stripInternalEventFields = (text: string): string =>
  text
    .trim()
    .replace(/\s*[（(]([^()（）]*)[）)]\s*$/u, (block, inner: string) =>
      INTERNAL_EVENT_FIELD.test(inner) ? '' : block
    )
    .trim()

const toPayloadPreview = (v: unknown): string => {
  const text = toStr(v).trim()
  if (!text) return ''

  try {
    const payload = JSON.parse(text) as Record<string, unknown>
    const summary = EVENT_SUMMARY_KEYS.map((key) => payload[key]).find(
      (value): value is string => typeof value === 'string' && value.trim().length > 0
    )
    return clipPayloadPreview(stripInternalEventFields(summary?.trim() || text))
  } catch {
    return clipPayloadPreview(stripInternalEventFields(text))
  }
}

// ============ 真实接口 ============

/** 总览聚合快照：stats + 近 7 日趋势，一次请求。 */
export async function fetchDashboardOverview(): Promise<{
  stats: OverviewStats
  trend: OrderTrendPoint[]
}> {
  const raw = await request.post<Record<string, any>>({ url: '/ws/dashboard/overview', data: {} })
  const stats: OverviewStats = {
    todayWaterOrders: toNum(raw.todayWaterOrders),
    todayWaterDone: toNum(raw.todayWaterDone),
    todayWaterDispensing: toNum(raw.todayWaterDispensing),
    todayWaterException: toNum(raw.todayWaterException),
    todayWaterCanceled: toNum(raw.todayWaterCanceled),
    todayRevenue: toNum(raw.todayRevenueFen),
    onlineDevices: toNum(raw.onlineDevices),
    totalDevices: toNum(raw.totalDevices),
    cmd24hSuccess: toNum(raw.cmd24hSuccess),
    cmd24hFail: toNum(raw.cmd24hFail),
    cmd24hTimeout: toNum(raw.cmd24hTimeout),
    // null 语义必须透传：无终态样本 ≠ 成功率 0
    cmdSuccessRate: toOptNum(raw.cmd24hSuccessRate) ?? null,
    pendingExceptionOrders: toNum(raw.pendingExceptionOrders),
    pendingAppeals: toNum(raw.pendingAppeals),
    pendingCouriers: toNum(raw.pendingCouriers),
    pendingDeliveries: toNum(raw.pendingDeliveries)
  }
  const trend: OrderTrendPoint[] = (Array.isArray(raw.trend) ? raw.trend : []).map(
    (p: Record<string, any>) => ({
      // yyyyMMdd → MM-DD（图表横轴展示格式）
      date: toStr(p.date).replace(/^\d{4}(\d{2})(\d{2})$/, '$1-$2'),
      waterOrders: toNum(p.waterOrders),
      rechargeOrders: toNum(p.rechargeOrders),
      deliveryOrders: toNum(p.deliveryOrders)
    })
  )
  return { stats, trend }
}

/** 最近指令与回执：真实指令分页前 10 条（默认按创建倒序）。 */
export async function fetchRecentCommands(): Promise<CommandMonitorRow[]> {
  const res = await request.post<{ list?: Record<string, any>[] }>({
    url: '/device/command/page',
    data: { current: 1, size: 10 }
  })
  return (res.list ?? []).map((row) => ({
    cmdNo: toStr(row.cmdNo),
    deviceNo: toStr(row.deviceNo),
    cmdType: toOptNum(row.cmdType),
    cmdStatus: toNum(row.cmdStatus),
    sentTime: toStr(row.sentTime) || undefined,
    ackTime: toStr(row.ackTime) || undefined,
    finishTime: toStr(row.finishTime) || undefined,
    failReason: toStr(row.failReason) || undefined
  }))
}

/** 最近审计事件：领域事件白名单分页前 8 条（白名单是安全边界，非白名单类型不出总览）。 */
export async function fetchRecentEvents(): Promise<AuditEventRow[]> {
  const res = await request.post<{ list?: Record<string, any>[] }>({
    url: '/api/domainEvent/whitelistPage',
    data: { current: 1, size: 8 }
  })
  return (res.list ?? []).map((row) => ({
    id: toStr(row.id),
    eventType: toOptNum(row.eventType),
    eventKey: toStr(row.eventKey),
    actorPortal: toOptNum(row.actorPortal),
    detail: toPayloadPreview(row.eventPayload),
    time: toStr(row.createTime) || undefined
  }))
}
