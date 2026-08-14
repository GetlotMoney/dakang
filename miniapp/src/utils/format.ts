import type { CourierAdmissionStatus, DeliveryTaskStatus } from '@/api/delivery'
import type { CardBlockCode, DeviceAvailability } from '@/api/device'
import type { MallOrderStatus, MallPayStatus } from '@/api/mall-trade'
import type { MessageSendStatus } from '@/api/message'
import type { AppealStatus, OrderStatus, OrderType, PayWay } from '@/api/order'

/** wd-tag 的 type 取值；default 渲染为无色标签。 */
export type TagTone = 'default' | 'primary' | 'danger' | 'warning' | 'success'

/** 金额展示：接口一律整数分，页面格式化为元（AGENTS：接口保留原单位）。 */
export function formatFen(fen: number): string {
  return `¥${(fen / 100).toFixed(2)}`
}

/**
 * 读数拆段：把一个量拆成「前缀符/整数位/小数位/单位」四段供 `.readout` 排版（粘死的字符串 CSS 拆不开）。
 * 边界：整段内容就是一个量时用 xxxParts；数字是一句话的一部分时仍用 formatXxx（wd-cell/Toast 等只接字符串）。
 */
export interface NumParts {
  /** 前缀符号，目前只有货币符 ¥。 */
  sigil?: string
  /** 整数位。 */
  value: string
  /** 小数位，含前导小数点；无小数时缺省。 */
  minor?: string
  /** 单位或量词；无单位时缺省。 */
  unit?: string
  /** 单位是否为中文量词（气口与拉丁单位不同）。 */
  cjkUnit?: boolean
}

/** 金额读数：与 formatFen 同口径（整数分 → 元，恒两位小数）。 */
export function fenParts(fen: number): NumParts {
  const [value, minor] = (fen / 100).toFixed(2).split('.')
  return { sigil: '¥', value, minor: `.${minor}` }
}

/** 水量读数：与 formatMl 同口径（整数毫升 → 升，整数不带小数）。 */
export function mlParts(ml: number): NumParts {
  const liters = ml / 1000
  const [value, minor] = (Number.isInteger(liters) ? String(liters) : liters.toFixed(1)).split('.')
  return { value, minor: minor ? `.${minor}` : undefined, unit: 'L' }
}

/** 计数读数：单 / 台 / 桶 / 件 / 人 / 天。量词是事实，不是说明。 */
export function countParts(n: number, unit: string): NumParts {
  return { value: String(n), unit, cjkUnit: true }
}

/** 手机号脱敏展示。未绑号时返回空串——别在模板里直接对可能缺省的号码 slice，会在未绑号账号上白屏。 */
export function maskPhone(phone?: string): string {
  return phone && phone.length === 11 ? `${phone.slice(0, 3)}****${phone.slice(-4)}` : ''
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

/**
 * 钱包在途分润行文案（D-421）：null=整行不渲染；金额>0 才出行；解冻时间缺省或非法时只报金额不报时间，
 * 不许出现「最早 — 起入账」半截话。
 */
export function pendingSplitLineText(pendingSplitFen: number, earliestUnfreezeTime?: string): string | null {
  if (!(pendingSplitFen > 0)) {
    return null
  }
  const time = formatBizTimeShort(earliestUnfreezeTime)
  return time === '—'
    ? `在途分润 ${formatFen(pendingSplitFen)}`
    : `在途分润 ${formatFen(pendingSplitFen)}，最早 ${time} 起入账`
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
 * 商城订单状态（字典 1393 同源，E2E-09 S2）：与取水/充值的 1341 是两套字典，不得复用 ORDER_STATUS_LABELS——
 * 同为 2 的取值一个是「已支付」一个是「已支付待履约」。
 */
export const MALL_ORDER_STATUS_LABELS: Record<MallOrderStatus, string> = {
  1: '待支付',
  2: '已支付待履约',
  3: '履约中',
  4: '已完成',
  5: '已取消',
  6: '已全额退款',
}

export const MALL_ORDER_STATUS_TONES: Record<MallOrderStatus, TagTone> = {
  1: 'warning',
  2: 'primary',
  3: 'primary',
  4: 'success',
  5: 'default',
  6: 'default',
}

/**
 * 商城售后状态色调（字典 1399 同源，E2E-09 S4）：只映射颜色不映射文案，
 * 状态名一律用服务端下发的 afterSaleStatusName，前端不另写中文。
 */
export const MALL_AFTER_SALE_STATUS_TONES: Record<number, TagTone> = {
  1: 'warning',
  2: 'primary',
  3: 'primary',
  4: 'primary',
  5: 'primary',
  6: 'success',
  7: 'danger',
  8: 'default',
  9: 'default',
}

/** 商城订单总额标签：只有支付成功事实存在时才能称「实付」；已取消未付款的订单不能写成已支付。 */
export function mallOrderAmountLabel(orderStatus: MallOrderStatus, payStatus?: MallPayStatus): string {
  if (payStatus === 2) {
    return '实付'
  }
  return orderStatus === 1 ? '应付' : '订单金额'
}

/** 商城支付来源：与充值同口径，按单如实展示，不受构建模式影响。 */
export function mallPaySourceLabel(paySource?: number): string | undefined {
  if (paySource === 1) {
    return '微信支付'
  }
  if (paySource === 2) {
    return '模拟支付'
  }
  return undefined
}

/** 规格快照展示：无规格键时回落 SKU 名，避免整行空白。 */
export function formatSpecs(specs: Record<string, string>, fallback = ''): string {
  return Object.values(specs).join(' / ') || fallback
}

/** 收益流水类型标签（字典 1378 同源；E2E-08 收益钱包） */
export const INCOME_FLOW_TYPE_LABELS: Record<number, string> = {
  1: '分润入账',
  2: '分润回退',
  3: '提现冻结',
  4: '提现完成',
  5: '提现驳回解冻',
}

/**
 * 支付方式(1346)：本表只回答「用哪种方式付」；有没有真走微信是每笔订单的事实，由服务端 paySource 按单记录。
 * 不得把构建期模式拼进文案后缀——后缀依赖构建开关而不是订单事实。
 */
export const PAY_WAY_LABELS: Record<PayWay, string> = {
  1: '微信支付',
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

// 与字典 1313 逐值同源；渠道语义另由 CHANNEL_LABELS 承担，状态列不得混入渠道话术（E2E-07 审查纠偏）
export const SEND_STATUS_LABELS: Record<MessageSendStatus, string> = {
  1: '待发送',
  2: '发送中',
  3: '发送失败',
  4: '已送达',
}

export const SEND_STATUS_TONES: Record<MessageSendStatus, TagTone> = {
  1: 'default',
  2: 'warning',
  3: 'danger',
  4: 'success',
}

export const MESSAGE_DOMAIN_LABELS: Record<string, string> = {
  water: '用水',
  card: '水卡',
  delivery: '配送',
  owner: '经营',
  system: '系统',
  mall: '商城',
}
