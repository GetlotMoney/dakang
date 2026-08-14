import type { EntityId, VolumeMl } from '@/api/common'
import type { CreateWaterOrderInput } from '@/api/order'

/**
 * S2 扫码报价冻结的前端约束，抽成纯函数供确认页与单测共用（vitest 在 node 环境无法加载 SFC，
 * 留在页面里测试只能抄副本、改坏不会红）。
 */

/** 与后端 {@code WaterBillingMath.ceilAmount} 逐字同式：ceil(毫升 × 分/升 ÷ 1000)。 */
export function estimatedAmountFen(planMl: number, unitPriceFenPerLiter: number): number {
  if (planMl <= 0) {
    return 0
  }
  // 写成 ceil(升) × 单价 在整升场景下结果相同，但一旦放开非整升就会与扣款额分叉
  return Math.ceil((planMl * unitPriceFenPerLiter) / 1000)
}

/**
 * 下单请求体装配：字段集合就是白名单本身，价格/金额/设备共键一律不出现——
 * 单价权威在服务端扫码会话冻结值，前端多报一个字段就多一个可被篡改的入口。
 */
export function buildCreateWaterOrderPayload(input: {
  scanSessionId: string
  cardId: EntityId
  waterTypeId: EntityId
  planMl: VolumeMl
  payWay: 2 | 3
}): CreateWaterOrderInput {
  return {
    scanSessionId: input.scanSessionId,
    cardId: input.cardId,
    waterTypeId: input.waterTypeId,
    planMl: input.planMl,
    payWay: input.payWay,
  }
}

/**
 * 提交闸的完整判据。hasEligibility 不可省（CARD-SCOPE「预检卡=下单卡」）：
 * 缺它则任何让 card 先于 eligibility 就位的改动都会让用户在零预检状态下按下确认。
 */
export interface WaterSubmitState {
  quoteInvalid: boolean
  ready: boolean
  hasContext: boolean
  hasCard: boolean
  hasEligibility: boolean
  /** 切卡预检在途：此刻 card/eligibility 仍是上一张卡的结论，放行就会扣到用户正要切走的那张卡。 */
  switchingCard: boolean
  availabilityNotice: string
  cardBlockNotice: string
  formError: string
}

export function canSubmitWater(state: WaterSubmitState): boolean {
  return !state.quoteInvalid
    && state.ready
    && state.hasContext
    && state.hasCard
    && state.hasEligibility
    && !state.switchingCard
    && !state.availabilityNotice
    && !state.cardBlockNotice
    && !state.formError
}

/**
 * 水卡区的四态。choose 的判据必须与模板选卡列表判据（usableCards.length > 1）严格同源，
 * 否则会叫用户去点一个不存在的列表；「有卡、未选中、也选不了」单列为 unresolved，文案指向重扫。
 */
export type WaterCardHint = 'none' | 'choose' | 'selected' | 'unresolved'

export function resolveCardHint(usableCardCount: number, hasCard: boolean): WaterCardHint {
  if (hasCard) {
    return 'selected'
  }
  if (usableCardCount <= 0) {
    return 'none'
  }
  // 与模板 v-if="usableCards.length > 1" 同源：列表会渲染才叫用户去选
  return usableCardCount > 1 ? 'choose' : 'unresolved'
}

/** 会话过期(5410)与报价变化(5411)都意味着本次扫码作废，处置动作相同：禁提交、要求重扫。 */
export const QUOTE_INVALID_CODES = ['SCAN_SESSION_EXPIRED', 'SCAN_QUOTE_CHANGED'] as const

export function isQuoteInvalidCode(code: unknown): boolean {
  return typeof code === 'string'
    && (QUOTE_INVALID_CODES as readonly string[]).includes(code)
}
