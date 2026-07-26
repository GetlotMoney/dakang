/**
 * 运营总览 API（demo 阶段：Mock 先行）
 *
 * 5+1 之「运营总览」——最小只读看板（CLAUDE.md 口径：订单/设备/命令/ACK/审计）。
 * 红线（REQ-067）：接真后指标口径必须来自后端数据库，不从飞书或 n8n 反算。
 * 当前固定数值只与订单中心 Mock 故事保持一致；设备中控等真实接口页面是另一数据源，
 * 页面必须明确标识数据边界，不得将固定 Mock 解释为设备与指令的实时聚合结果。
 */

import { fetchDemoAuditEvents, type DemoAuditEvent } from '@/api/demo-audit'

// ============ 类型定义 ============

/** 核心指标卡 */
export interface OverviewStats {
  /** 今日取水订单数 */
  todayWaterOrders: number
  /** 今日营收(分) */
  todayRevenue: number
  /** 在线设备/设备总数 */
  onlineDevices: number
  totalDevices: number
  /** 指令 24h 成功率(%) */
  cmdSuccessRate: number
  /** 待处理事项：异常订单 */
  pendingExceptionOrders: number
  /** 待处理事项：申诉 */
  pendingAppeals: number
  /** 待处理事项：配送员审核 */
  pendingCouriers: number
  /** 待处理事项：待接配送单 */
  pendingDeliveries: number
}

/** 近 7 日订单趋势点 */
export interface OrderTrendPoint {
  date: string
  waterOrders: number
  rechargeOrders: number
  deliveryOrders: number
}

/** 指令回执监控行（命令/ACK 看板，MVP 验收口径） */
export interface CommandMonitorRow {
  cmdNo: string
  deviceNo: string
  cmdTypeLabel: string
  /** 指令状态(1321) */
  cmdStatus: number
  sentTime: string
  ackTime?: string
  finishTime?: string
  failReason?: string
}

/** 审计事件行：Demo 阶段由共享内存事件源提供，接真后映射 ws_domain_event。 */
export type AuditEventRow = DemoAuditEvent

// ============ Mock 数据 ============

/**
 * 指标与订单中心 07-12 运营快照严格咬合（T2 审查 M1/M2 整改）：
 * - 今日取水 5 单：1001完成 1002异常 1003出水中 1009失败取消 1010超时异常
 * - 今日营收 4700 分 = 取水1001(200) + 配送1007(3000) + 配送1008(1500)，出水中/取消/未扣费不计
 * - 固定场景设备 2 台：DK-DEV-0001 在线，DK-DEV-0002 离线（审计事件#6）
 * - 指令成功率 46.2% = 成功6 / (6+失败2+超时5)，与注脚同口径
 * - 异常待补偿 2 单：1002 + 1010
 */
const MOCK_STATS: OverviewStats = {
  todayWaterOrders: 5,
  todayRevenue: 4700,
  onlineDevices: 1,
  totalDevices: 2,
  cmdSuccessRate: 46.2,
  pendingExceptionOrders: 2,
  pendingAppeals: 1,
  pendingCouriers: 1,
  pendingDeliveries: 1
}

/** 订单趋势与订单列表固定故事一致；07-11 两单为 REQ-050 ACK 异常验收样例。 */
const MOCK_TREND: OrderTrendPoint[] = [
  { date: '07-06', waterOrders: 0, rechargeOrders: 0, deliveryOrders: 0 },
  { date: '07-07', waterOrders: 0, rechargeOrders: 0, deliveryOrders: 0 },
  { date: '07-08', waterOrders: 0, rechargeOrders: 0, deliveryOrders: 0 },
  { date: '07-09', waterOrders: 0, rechargeOrders: 0, deliveryOrders: 0 },
  { date: '07-10', waterOrders: 0, rechargeOrders: 1, deliveryOrders: 0 },
  { date: '07-11', waterOrders: 2, rechargeOrders: 1, deliveryOrders: 1 },
  { date: '07-12', waterOrders: 5, rechargeOrders: 0, deliveryOrders: 2 }
]

/** 固定指令场景（成功6/失败2/超时5），不表示设备中控真实接口的当前统计。 */
const MOCK_COMMANDS: CommandMonitorRow[] = [
  {
    cmdNo: 'CMD20260712162849678024',
    deviceNo: 'DK-DEV-0001',
    cmdTypeLabel: '查询状态',
    cmdStatus: 5,
    sentTime: '20260712162849',
    ackTime: '20260712162849',
    finishTime: '20260712162851',
    failReason: '阀门卡滞，执行失败'
  },
  {
    cmdNo: 'CMD20260712162422320786',
    deviceNo: 'DK-DEV-0001',
    cmdTypeLabel: '查询状态',
    cmdStatus: 4,
    sentTime: '20260712162422',
    finishTime: '20260712162425'
  },
  {
    cmdNo: 'CMD20260712162338419006',
    deviceNo: 'DK-DEV-0001',
    cmdTypeLabel: '查询状态',
    cmdStatus: 4,
    sentTime: '20260712162338',
    ackTime: '20260712162338',
    finishTime: '20260712162340'
  },
  {
    cmdNo: 'CMD20260712162152207522',
    deviceNo: 'DK-DEV-0001',
    cmdTypeLabel: '查询状态',
    cmdStatus: 6,
    sentTime: '20260712162152',
    finishTime: '20260712162226',
    failReason: '下发后 30 秒未收到设备回执'
  },
  {
    cmdNo: 'CMD202607121539296601',
    deviceNo: 'DK-DEV-0001',
    cmdTypeLabel: '锁机',
    cmdStatus: 4,
    sentTime: '20260712153929',
    ackTime: '20260712153949',
    finishTime: '20260712153950'
  }
]

const delay = <T>(data: T, ms = 200): Promise<T> =>
  new Promise((resolve) => setTimeout(() => resolve(data), ms))

// ============ Mock 接口（前端可直接调用运行） ============

/** 总览核心指标 */
export function fetchOverviewStats(): Promise<OverviewStats> {
  return delay(MOCK_STATS)
}
// TODO: 替换为真实接口
// export function fetchOverviewStats() {
//   return request.post<OverviewStats>({ url: '/api/dashboard/stats', data: {} })
// }

/** 近 7 日订单趋势 */
export function fetchOrderTrend(): Promise<OrderTrendPoint[]> {
  return delay(MOCK_TREND)
}
// TODO: 替换为真实接口
// export function fetchOrderTrend() {
//   return request.post<OrderTrendPoint[]>({ url: '/api/dashboard/orderTrend', data: {} })
// }

/** 最近指令与回执（命令/ACK 看板） */
export function fetchRecentCommands(): Promise<CommandMonitorRow[]> {
  return delay(MOCK_COMMANDS)
}
// TODO: 替换为真实接口
// export function fetchRecentCommands() {
//   return request.post<CommandMonitorRow[]>({ url: '/api/dashboard/recentCommands', data: {} })
// }

/** 最近审计事件（共享 Demo 审计源；接真后替换为 ws_domain_event 聚合接口） */
export function fetchRecentEvents(): Promise<AuditEventRow[]> {
  return fetchDemoAuditEvents(30)
}
// TODO: 替换为真实接口
// export function fetchRecentEvents() {
//   return request.post<AuditEventRow[]>({ url: '/api/dashboard/recentEvents', data: {} })
// }
