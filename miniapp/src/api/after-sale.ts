import type { BusinessTime, MoneyFen, VolumeMl } from './common'
import type { TagTone } from '@/utils/format'
import { ContractError } from './common'
import { strictInt, strictNonNegativeInt } from './delivery-normalize'
import { withRealSession } from './real-session'
import { post } from './request'
import { formatFen, formatMl } from '@/utils/format'

/**
 * 小程序售后只读契约（E2E-04 包E）。
 *
 * <h3>本模块的边界，先于任何字段定义</h3>
 * - **只读进度**：退款请求、Refund-Sim 事实回放、补送生成、资金返还执行全部是 PC 运营端
 *   动作（`/order/after-sale/**`，权限 `order:aftersale:handle`）。小程序不提供、也不得提供
 *   任何触发按钮——C 端能点一下就退钱的入口，是资金铁律里最先要堵死的那个。
 * - **金额与水量一律只读**：四元额度（水品分 / 配送费分 / 水品毫升 / 合计分）由服务端按下单
 *   快照算好并冻结。本模块只做格式化，**不做加减、不做合计、不做折算**；合计取服务端
 *   `refundAmount` 原值，前端把三项加起来看着"也对"的那一刻，就是两套账的开始。
 * - **状态口径不自造**：售后是否完成只认服务端 `actionStatus=3`。补送单生成 ≠ 补送完成，
 *   必须等配送员实际回签、服务端把动作置为 SUCCESS 才允许出现"已完成"字样（包C 结论）。
 * - **资格不自算**：能不能取消由服务端判定并下发，页面只按下发结果显隐入口。
 *
 * <h3>与后端字典的对应</h3>
 * 1370 售后来源 / 1371 动作类型 / 1372 执行状态 / 1373 退款来源，取值与
 * `AfterSaleEnum`、`ws_refund` 同源；未登记的值一律 fail-closed（隐藏而不是猜一个展示）。
 */

// ==================== 字典类型（与后端 AfterSaleEnum 同源） ====================

/** 售后来源(1370)：1配送取消 2配送申诉 3取水异常核账 4充值退款（包D-5）。 */
export type AfterSaleSourceType = 1 | 2 | 3 | 4

/** 售后动作类型(1371)：1卡内退款 2卡内补偿 3机构退款 4补送。 */
export type AfterSaleActionType = 1 | 2 | 3 | 4

/** 售后执行状态(1372)：1待执行 2执行中 3已完成 4可重试 5需人工对账 6已终止。 */
export type AfterSaleActionStatus = 1 | 2 | 3 | 4 | 5 | 6

/** 退款来源(1373)：1微信 2Refund-Sim。 */
export type RefundSource = 1 | 2

/**
 * 面向用户的售后进度（只读）。
 *
 * 字段名与 PC 台账行 `AdminAfterSaleActionItemVo` 保持一致，`resendOrderNo/resendTaskNo`
 * 是补送结果（`RESULT_ORDER_ID/RESULT_TASK_ID`）在用户侧的业务号投影，`refundSource`
 * 取自 `ws_refund`。用户侧**不下发**运营人员、重试次数、原始退款报文等内部字段。
 */
export interface AfterSaleProgress {
  /** 售后号（确定性派生，用户可据此向客服对单）。 */
  afterSaleNo: string
  sourceType: AfterSaleSourceType
  actionType: AfterSaleActionType
  actionStatus: AfterSaleActionStatus
  /** 运营批准的受影响数量(桶)；补送/按数量补偿时有值。 */
  approvedCount?: number
  /** 水品权益返还金额(分)，payWay=2 场景。 */
  refundProductFen?: MoneyFen
  /** 配送费返还金额(分)。 */
  refundServiceFen?: MoneyFen
  /** 水品权益返还水量(毫升)，payWay=3 场景。 */
  refundProductMl?: VolumeMl
  /** 返还金额合计(分)：**服务端算好的原值**，前端不重算也不校验相加。 */
  refundAmount?: MoneyFen
  /** 退款来源(1373)；仅机构退款(actionType=3)有值。 */
  refundSource?: RefundSource
  /** 补送子订单号；仅 actionType=4 且已生成时有值。 */
  resendOrderNo?: string
  /** 补送任务号；仅 actionType=4 且已生成时有值。 */
  resendTaskNo?: string
  /** 终态时间；未到终态为空。 */
  finishTime?: BusinessTime
}

/**
 * 待接单取消资格。**唯一来源是服务端**——"什么状态可以取消"是包A 事务里的判定
 * （任务未被接单 + 订单已支付 + 归属本人 + 无在途售后），页面把它抄一遍必然漂移，
 * 且漂移的方向永远是"多给一个入口"。缺省（服务端未下发）即不显示入口。
 */
export interface AfterSaleCancelEligibility {
  allowed: boolean
  /** 不允许时的服务端说明；页面原样展示，不另造文案。 */
  reason?: string
}

// ==================== 展示文案（唯一出处） ====================

export const AFTER_SALE_SOURCE_LABELS: Record<AfterSaleSourceType, string> = {
  1: '配送取消',
  2: '配送申诉',
  3: '取水异常核账',
  4: '充值退款',
}

export const AFTER_SALE_ACTION_TYPE_LABELS: Record<AfterSaleActionType, string> = {
  1: '卡内退款',
  2: '卡内补偿',
  3: '机构退款',
  4: '补送',
}

export const AFTER_SALE_STATUS_LABELS: Record<AfterSaleActionStatus, string> = {
  1: '待执行',
  2: '执行中',
  3: '已完成',
  4: '待重试',
  5: '需人工对账',
  6: '已终止',
}

export const AFTER_SALE_STATUS_TONES: Record<AfterSaleActionStatus, TagTone> = {
  1: 'warning',
  2: 'primary',
  3: 'success',
  4: 'warning',
  5: 'danger',
  6: 'default',
}

/**
 * 退款来源文案（1373）。
 *
 * **R0-8**：当前机构退款链路由 Refund-Sim 承载，展示必须写「Refund-Sim」。把模拟退款
 * 渲染成「微信退款」，用户会去微信账单里找一笔根本不存在的退款；反过来把真实微信退款
 * 写成 Refund-Sim 同样是假话。所以两个取值各自照实展示，未登记值不解释。
 */
export const REFUND_SOURCE_LABELS: Record<RefundSource, string> = {
  1: '微信支付退款',
  2: '模拟退款通道',
}

/** 页面固定说明：小程序在售后链路里的角色。 */
export const AFTER_SALE_READONLY_NOTE
  = '退款与补送由客服处理。'

/**
 * 售后结局文案。**未到 `actionStatus=3` 一律是"处理中/待补送"**，不得出现"成功/已到账/已补送"。
 *
 * 补送(4) 与资金返还(1/2/3) 分开措辞：补送的完成条件是配送员回签（包C
 * `completeOnSigned` 才把动作置 SUCCESS），资金返还的完成条件是返还事务落账。
 */
export function afterSaleResultText(progress: AfterSaleProgress): { text: string, tone: TagTone } {
  const tone = AFTER_SALE_STATUS_TONES[progress.actionStatus]
  if (progress.actionType === 4) {
    switch (progress.actionStatus) {
      case 3:
        return { text: '补送已完成', tone }
      case 5:
        return { text: '补送需人工对账，请等待客服处理', tone }
      case 6:
        return { text: '补送已终止', tone }
      default:
        return {
          text: progress.resendOrderNo
            ? '补送单已生成，等待配送签收'
            : '待补送',
          tone,
        }
    }
  }
  switch (progress.actionStatus) {
    case 3:
      return { text: '返还已完成', tone }
    case 5:
      return { text: '需人工对账，请等待客服处理', tone }
    case 6:
      return { text: '售后已终止', tone }
    default:
      return { text: '退款处理中', tone }
  }
}

/** 展示行（label/value 均为最终文案）。 */
export interface AfterSaleRow {
  label: string
  value: string
}

/**
 * 返还额度展示行：逐项格式化服务端下发的原值。
 *
 * 只做"有值就展示"，**没有任何求和、折算或补零**：合计行取 `refundAmount` 原值，
 * 缺失就不展示合计，绝不用三项加出来的数字顶上。
 */
export function afterSaleAmountRows(progress: AfterSaleProgress): AfterSaleRow[] {
  const rows: AfterSaleRow[] = []
  if (progress.refundProductFen !== undefined) {
    rows.push({ label: '水品返还金额', value: formatFen(progress.refundProductFen) })
  }
  if (progress.refundProductMl !== undefined) {
    rows.push({ label: '水品返还水量', value: formatMl(progress.refundProductMl) })
  }
  if (progress.refundServiceFen !== undefined) {
    rows.push({ label: '配送费返还', value: formatFen(progress.refundServiceFen) })
  }
  if (progress.refundAmount !== undefined) {
    rows.push({ label: '返还合计', value: formatFen(progress.refundAmount) })
  }
  return rows
}

/** 退款来源展示：仅机构退款有来源；未登记值按"未知"呈现，不猜通道。 */
export function refundSourceText(progress: AfterSaleProgress): string | undefined {
  if (progress.refundSource === undefined) {
    return undefined
  }
  return REFUND_SOURCE_LABELS[progress.refundSource] ?? '退款来源未知'
}

/** 取消入口显隐：只认服务端下发的 allowed===true，其余（含未下发）一律不显示。 */
export function canShowCancelEntry(eligibility: AfterSaleCancelEligibility | undefined): boolean {
  return eligibility?.allowed === true
}

/**
 * 「补送」标识判定：**两个服务端值相等**才成立（本单订单号 === 售后动作回填的补送单号），
 * 不是按"金额为 0 / 无回收桶"这类特征去猜——特征命中的那天就是误标的那天。
 */
export function isResendOrderNo(progress: AfterSaleProgress | undefined, orderNo: string): boolean {
  return Boolean(progress?.resendOrderNo) && progress?.resendOrderNo === orderNo
}

/** 同上，任务维度。 */
export function isResendTaskNo(progress: AfterSaleProgress | undefined, taskNo: string): boolean {
  return Boolean(progress?.resendTaskNo) && progress?.resendTaskNo === taskNo
}

// ==================== 后端原样结构与归一化 ====================

function optionalText(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : undefined
}

function optionalBizTime(value: unknown): BusinessTime | undefined {
  return typeof value === 'string' && /^\d{14}$/.test(value) ? value : undefined
}

/** 金额/水量：null/undefined 视为未下发；其余必须是非负安全整数，畸形返回 'invalid'。 */
function optionalAmount(value: unknown): number | undefined | 'invalid' {
  if (value == null) {
    return undefined
  }
  const normalized = strictNonNegativeInt(value)
  return normalized === undefined ? 'invalid' : normalized
}

/**
 * 售后进度归一化。
 *
 * 任一必需字段缺失、任一额度畸形、任一枚举越界 → **整块返回 undefined（隐藏区块）**。
 * 这里刻意不抛错：售后是订单详情上的附加证据，为了它让整页 404 是过度反应；
 * 但也绝不允许"部分字段能用就先渲染"——一个漏掉水量列的退款展示，比不展示更误导。
 */
export function normalizeAfterSaleProgress(value: unknown): AfterSaleProgress | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return undefined
  }
  const raw = value as Record<string, unknown>
  const afterSaleNo = optionalText(raw.afterSaleNo)
  const sourceType = strictInt(raw.sourceType)
  const actionType = strictInt(raw.actionType)
  const actionStatus = strictInt(raw.actionStatus)
  if (!afterSaleNo
    // 上界随 1370 字典一起放到 4：留在 3 会让充值退款的进度被整条丢掉，
    // 而丢掉的表现是「用户侧什么都不显示」，比显示一个陌生标签更难排查
    || sourceType === undefined || sourceType < 1 || sourceType > 4
    || actionType === undefined || actionType < 1 || actionType > 4
    || actionStatus === undefined || actionStatus < 1 || actionStatus > 6) {
    return undefined
  }
  const approvedCount = optionalAmount(raw.approvedCount)
  const refundProductFen = optionalAmount(raw.refundProductFen)
  const refundServiceFen = optionalAmount(raw.refundServiceFen)
  const refundProductMl = optionalAmount(raw.refundProductMl)
  const refundAmount = optionalAmount(raw.refundAmount)
  if ([approvedCount, refundProductFen, refundServiceFen, refundProductMl, refundAmount]
    .includes('invalid')) {
    return undefined
  }
  const refundSourceValue = raw.refundSource == null ? undefined : strictInt(raw.refundSource)
  if (raw.refundSource != null && refundSourceValue !== 1 && refundSourceValue !== 2) {
    return undefined
  }
  return {
    afterSaleNo,
    sourceType: sourceType as AfterSaleSourceType,
    actionType: actionType as AfterSaleActionType,
    actionStatus: actionStatus as AfterSaleActionStatus,
    approvedCount: approvedCount as number | undefined,
    refundProductFen: refundProductFen as number | undefined,
    refundServiceFen: refundServiceFen as number | undefined,
    refundProductMl: refundProductMl as number | undefined,
    refundAmount: refundAmount as number | undefined,
    refundSource: refundSourceValue as RefundSource | undefined,
    resendOrderNo: optionalText(raw.resendOrderNo),
    resendTaskNo: optionalText(raw.resendTaskNo),
    finishTime: optionalBizTime(raw.finishTime),
  }
}

/**
 * 取消资格归一化。同时接受两种下发形态：布尔 `cancellable` 与结构化
 * `{allowed, reason}`；两者都缺就是 undefined = 不显示入口。
 * **只有显式 true 才算允许**，"真值"（1、'true'、非空字符串）一律不算——
 * 资金动作的入口不接受宽松判定。
 */
export function normalizeCancelEligibility(value: unknown): AfterSaleCancelEligibility | undefined {
  if (typeof value === 'boolean') {
    return { allowed: value }
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return undefined
  }
  const raw = value as Record<string, unknown>
  if (typeof raw.allowed !== 'boolean') {
    return undefined
  }
  return { allowed: raw.allowed, reason: optionalText(raw.reason) }
}

// ==================== 适配器 ====================

export const afterSaleEndpoints = {
  /** 待接单取消（E2E-04 包A）：返回**面向用户的结果文案**，不是布尔成功位。 */
  deliveryCancel: '/mini/delivery/order/cancel',
} as const

export interface AfterSaleApi {
  /**
   * 待接单取消配送订单。
   *
   * 返回服务端文案并要求页面**原样展示**：「已退还至水卡」与「退款处理中」是两种结局，
   * 订单状态先于资金独占，业务段提交而资金段在途时把它显示成"已退款"就是谎报到账。
   */
  cancelPendingDeliveryOrder: (orderNo: string) => Promise<string>
}

const realAfterSaleApi: AfterSaleApi = {
  async cancelPendingDeliveryOrder(orderNo) {
    if (!orderNo) {
      throw new ContractError('ORDER_NO_REQUIRED', '缺少订单号，无法提交取消')
    }
    return withRealSession(async () => {
      // 归属、任务是否仍待接单、资金返还全部在服务端事务内裁决；这里只发起并转述结论。
      const raw = await post<string | null>(afterSaleEndpoints.deliveryCancel, { orderNo })
      const text = typeof raw === 'string' ? raw.trim() : ''
      if (!text) {
        throw new ContractError(
          'AFTER_SALE_CONTRACT_BROKEN',
          '取消结果未知，请在订单详情确认后再操作',
        )
      }
      return text
    })
  },
}

/**
 * 售后动作挂在 delivery 域下（端点即 `/mini/delivery/order/cancel`），不新增运行模式域：
 * 新增域要同步 `buildRuntimeModes` 与 e2e 安全闸的段序，而本包没有任何独立于配送域的
 * 真实端点，多一个域只会多一处漏配。
 */
export const afterSaleApi = realAfterSaleApi
