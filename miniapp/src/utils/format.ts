import type { CourierAdmissionStatus, DeliveryTaskStatus } from '@/api/delivery'
import type { CardBlockCode, DeviceAvailability } from '@/api/device'
import type { MessageSendStatus } from '@/api/message'
import type { AppealStatus, OrderStatus, OrderType, PayWay } from '@/api/order'
import { currentMode } from '@/api/runtime'

/** wd-tag 的 type 取值；default 渲染为无色标签。 */
export type TagTone = 'default' | 'primary' | 'danger' | 'warning' | 'success'

/** 金额展示：接口一律整数分，页面格式化为元（AGENTS：接口保留原单位）。 */
export function formatFen(fen: number): string {
  return `¥${(fen / 100).toFixed(2)}`
}

/** 水量展示：接口一律整数毫升，页面格式化为升。 */
export function formatMl(ml: number): string {
  const liters = ml / 1000
  return `${Number.isInteger(liters) ? liters : liters.toFixed(1)}L`
}

/** yyyyMMddHHmmss → yyyy-MM-dd HH:mm:ss；空值返回占位符。 */
export function formatBizTime(time?: string): string {
  if (!time || time.length !== 14) {
    return '—'
  }
  return (
    `${time.slice(0, 4)}-${time.slice(4, 6)}-${time.slice(6, 8)}`
    + ` ${time.slice(8, 10)}:${time.slice(10, 12)}:${time.slice(12, 14)}`
  )
}

/** yyyyMMddHHmmss → MM-dd HH:mm，用于列表紧凑展示。 */
export function formatBizTimeShort(time?: string): string {
  if (!time || time.length !== 14) {
    return '—'
  }
  return `${time.slice(4, 6)}-${time.slice(6, 8)} ${time.slice(8, 10)}:${time.slice(10, 12)}`
}

export const ORDER_TYPE_LABELS: Record<OrderType, string> = {
  1: '扫码取水',
  2: '购卡充值',
  3: '水配送',
}

export const ORDER_STATUS_LABELS: Record<OrderStatus, string> = {
  1: '待支付',
  2: '已支付',
  3: '出水中',
  4: '已完成',
  5: '已取消',
  6: '异常待补偿',
  7: '已退款',
  8: '部分退款',
}

export const ORDER_STATUS_TONES: Record<OrderStatus, TagTone> = {
  1: 'warning',
  2: 'primary',
  3: 'primary',
  4: 'success',
  5: 'default',
  6: 'danger',
  7: 'default',
  8: 'warning',
}

/**
 * 支付方式(1346)。
 *
 * `payWay=1` 的后缀随充值域模式变化：接真后这条链路由 Pay-Sim 承载，
 * 仍写「待接入」会让一笔**已经真实入账**的订单看起来像没付过款——
 * 演示时最容易造成误判的正是这种陈旧文案。真实微信支付接入后应去掉后缀。
 */
export const PAY_WAY_LABELS: Record<PayWay, string> = {
  1: currentMode('recharge') === 'real' ? '微信支付（当前由 Pay-Sim 模拟）' : '微信支付（待接入）',
  2: '水卡余额',
  3: '水卡水量',
}

export const TASK_STATUS_LABELS: Record<DeliveryTaskStatus, string> = {
  1: '待接单',
  2: '已接单',
  3: '配送中',
  4: '已送达待确认',
  5: '已签收',
  6: '已取消',
  7: '申诉中',
}

export const TASK_STATUS_TONES: Record<DeliveryTaskStatus, TagTone> = {
  1: 'warning',
  2: 'primary',
  3: 'primary',
  4: 'warning',
  5: 'success',
  6: 'default',
  7: 'danger',
}

export const ADMISSION_STATUS_LABELS: Record<CourierAdmissionStatus, string> = {
  0: '未提交',
  1: '待审核',
  2: '已启用',
  3: '已停用',
  4: '审核驳回',
}

export const ADMISSION_STATUS_TONES: Record<CourierAdmissionStatus, TagTone> = {
  0: 'default',
  1: 'warning',
  2: 'success',
  3: 'default',
  4: 'danger',
}

export const APPEAL_STATUS_LABELS: Record<AppealStatus, string> = {
  1: '待处理',
  2: '成立待补偿',
  3: '不成立驳回',
  4: '已撤销',
  5: '补送待执行',
}

export const APPEAL_STATUS_TONES: Record<AppealStatus, TagTone> = {
  1: 'warning',
  2: 'success',
  3: 'danger',
  4: 'default',
  5: 'primary',
}

export const AVAILABILITY_LABELS: Record<DeviceAvailability, string> = {
  AVAILABLE: '可取水',
  DEVICE_OFFLINE: '设备离线',
  FAULT_E001: '设备故障 E001',
  FAULT_E003: '设备故障 E003',
  FAULT_E004: '设备故障 E004',
  DEVICE_UNAVAILABLE: '设备暂不可用',
  NO_AVAILABLE_OUTLET: '无可用出水口',
}

export const CARD_BLOCK_LABELS: Record<CardBlockCode, string> = {
  CARD_MISSING: '暂无可用水卡',
  CARD_NOT_ACCESSIBLE: '水卡不可用',
  CARD_SCOPE_INVALID: '水卡范围未配置',
  CARD_SCOPE_DENIED: '水卡不适用当前设备',
  CARD_FROZEN: '水卡已冻结',
  CARD_EXPIRED: '水卡已过期',
  CARD_CANCELLED: '水卡已注销',
}

/** 设备指令状态（PC 字典 1321）。 */
export const COMMAND_STATUS_LABELS: Record<number, string> = {
  1: '待下发',
  2: '已下发',
  3: '已回执',
  4: '执行成功',
  5: '执行失败',
  6: '超时',
  7: '部分完成',
}

/** 水卡状态（PC 字典 1332）。 */
export const CARD_STATUS_LABELS: Record<number, string> = {
  1: '正常',
  2: '冻结',
  3: '已过期',
  4: '已注销',
}

export const CARD_STATUS_TONES: Record<number, TagTone> = {
  1: 'success',
  2: 'danger',
  3: 'warning',
  4: 'default',
}

export const SEND_STATUS_LABELS: Record<MessageSendStatus, string> = {
  1: '待发送',
  2: '发送成功',
  3: '发送失败',
  4: '站内消息',
}

export const SEND_STATUS_TONES: Record<MessageSendStatus, TagTone> = {
  1: 'default',
  2: 'success',
  3: 'danger',
  4: 'primary',
}

export const MESSAGE_DOMAIN_LABELS: Record<string, string> = {
  water: '用水',
  card: '水卡',
  delivery: '配送',
  owner: '经营',
  system: '系统',
}
