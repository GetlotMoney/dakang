import type { EntityId, VolumeMl } from '@/api/common'
import type { CreateWaterOrderInput } from '@/api/order'

/**
 * S2 扫码报价冻结的前端约束，抽成纯函数供确认页与单测共用。
 *
 * <p>抽出来的唯一目的是可被测试直接加载：这三条约束此前写在 confirm.vue 的 setup 里，
 * 而 vitest 跑在 environment:'node' 且未装 Vue SFC 装置，测试只能在自己文件里抄一份副本断言，
 * 页面改坏时不会红——那样的测试不是回归闸，是摆设。</p>
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
 * 下单请求体装配：字段集合就是白名单本身。
 *
 * <p>价格、金额与设备共键一律不出现——单价的权威是服务端扫码会话里的冻结值，
 * 设备/出水口由会话解引用。前端多报一个字段，服务端就多一个可被篡改的入口。</p>
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
 * 提交闸的完整判据。
 *
 * <p>{@code hasEligibility} 是本函数存在的主要理由：CARD-SCOPE 契约要求「预检卡 = 下单卡」，
 * 而预检结论只在选定某张卡之后才产生。没有这一项，任何让 card 先于 eligibility 就位的改动
 * （例如给多卡场景加一个"记住上次选卡"的默认值）都会让用户在零预检的状态下按下确认。
 * 服务端事务内仍会二次校验并整体回滚，但用户会先看到一次莫名其妙的失败。</p>
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
 * 水卡区的四态。
 *
 * <p>{@code choose} 此前在页面上不存在：整个内容区被 `context && eligibility` 门住，
 * 而多卡用户不做预检、eligibility 恒 null，于是选卡列表本身也不渲染——用户永远拿不到
 * 触发预检的入口，页面自锁。</p>
 *
 * <p><b>{@code choose} 的判据必须与模板里选卡列表的判据（{@code usableCards.length > 1}）
 * 严格同源</b>，否则会出现「叫用户去点一个页面上不存在的列表」：只有 1 张卡却没选中时
 * （预检失败），若也判成 choose，页面就会对单卡用户说"存在多张可用水卡，请先选择"，
 * 而下面一张卡都没有。这种「有卡、未选中、也选不了」的异常态单列为 {@code unresolved}，
 * 文案必须说实话并指向重扫。</p>
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
