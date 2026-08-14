import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import {
  buildCreateWaterOrderPayload,
  canSubmitWater,
  estimatedAmountFen,
  isQuoteInvalidCode,
  resolveCardHint,
} from './quote'

/**
 * S2 扫码报价冻结的前端约束。钉三件事：预计金额与后端 ceilAmount 同式；下单请求体不携带
 * 价格/金额/设备共键；5410/5411 都判为报价失效。被断言的必须是 confirm.vue 真正调用的
 * ./quote 实现——抄副本断言副本会给出虚假的回归保护。
 */
describe('s2 扫码报价前端约束', () => {
  it('预计金额与后端 ceil 公式一致', () => {
    expect(estimatedAmountFen(5000, 20)).toBe(100)
    expect(estimatedAmountFen(1000, 20)).toBe(20)
    // 非整升与非整除场景：必须向上取整到分，排除「ceil(升) × 单价」这种放开非整升即分叉的写法
    expect(estimatedAmountFen(1500, 33)).toBe(50)
    expect(estimatedAmountFen(1, 1)).toBe(1)
    expect(estimatedAmountFen(0, 20)).toBe(0)
    expect(estimatedAmountFen(-1, 20)).toBe(0)
  })

  it('下单请求体不发送价格、金额与设备共键', () => {
    const payload = buildCreateWaterOrderPayload({
      scanSessionId: 'S1',
      cardId: 'C1',
      waterTypeId: '8',
      planMl: 5000,
      payWay: 2,
    })

    // 价格与水种的权威在服务端扫码会话；设备共键由会话解引用，前端一律不自报
    for (const forbidden of ['price', 'unitPriceFenPerLiter', 'amount', 'orderAmount', 'deviceId', 'outletId', 'userId']) {
      expect(Object.prototype.hasOwnProperty.call(payload, forbidden)).toBe(false)
    }
    expect(Object.keys(payload).sort())
      .toEqual(['cardId', 'payWay', 'planMl', 'scanSessionId', 'waterTypeId'])
  })

  it('多传的字段不会被带进请求体', () => {
    // 装配函数是白名单本身：调用方即便塞进价格字段也必须被丢弃
    const payload = buildCreateWaterOrderPayload({
      scanSessionId: 'S1',
      cardId: 'C1',
      waterTypeId: '8',
      planMl: 5000,
      payWay: 2,
      unitPriceFenPerLiter: 20,
      orderAmount: 100,
    } as never)

    expect(Object.keys(payload).sort())
      .toEqual(['cardId', 'payWay', 'planMl', 'scanSessionId', 'waterTypeId'])
  })

  it('5410 与 5411 都判为报价失效，其它码不判', () => {
    expect(isQuoteInvalidCode('SCAN_SESSION_EXPIRED')).toBe(true)
    expect(isQuoteInvalidCode('SCAN_QUOTE_CHANGED')).toBe(true)
    expect(isQuoteInvalidCode('QR_EXPIRED')).toBe(false)
    expect(isQuoteInvalidCode('INVALID_QR_CODE')).toBe(false)
    expect(isQuoteInvalidCode(undefined)).toBe(false)
  })
})

/** 可提交的基线状态：单卡自动选中、预检通过、无任何阻断。 */
function submittable() {
  return {
    quoteInvalid: false,
    ready: true,
    hasContext: true,
    hasCard: true,
    hasEligibility: true,
    switchingCard: false,
    availabilityNotice: '',
    cardBlockNotice: '',
    formError: '',
  }
}

describe('s2-r 确认页提交闸', () => {
  it('基线状态可提交', () => {
    expect(canSubmitWater(submittable())).toBe(true)
  })

  it('任一必要条件缺失即不可提交', () => {
    // 逐项翻转：每一条都必须能单独否决，否则该条就是摆设
    expect(canSubmitWater({ ...submittable(), quoteInvalid: true })).toBe(false)
    expect(canSubmitWater({ ...submittable(), ready: false })).toBe(false)
    expect(canSubmitWater({ ...submittable(), hasContext: false })).toBe(false)
    expect(canSubmitWater({ ...submittable(), hasCard: false })).toBe(false)
    expect(canSubmitWater({ ...submittable(), availabilityNotice: '设备离线' })).toBe(false)
    expect(canSubmitWater({ ...submittable(), cardBlockNotice: '水卡已冻结' })).toBe(false)
    expect(canSubmitWater({ ...submittable(), formError: '取水量必须大于 0' })).toBe(false)
  })

  it('卡已选但预检未回时不可提交（CARD-SCOPE 预检卡=下单卡）', () => {
    // 多卡场景下 card 与 eligibility 分两步就位；缺了这一条就会在零预检状态下放行提交
    expect(canSubmitWater({ ...submittable(), hasEligibility: false })).toBe(false)
  })

  it('切卡预检在途时不可提交（否则点了 B 却扣 A）', () => {
    // 在途期间 card/eligibility 仍是上一张卡的结论，此刻放行就是扣用户正要切走的那张卡
    expect(canSubmitWater({ ...submittable(), switchingCard: true })).toBe(false)
  })
})

describe('s2-r 水卡区四态', () => {
  it('零卡为 none，多卡未选为 choose，已选为 selected', () => {
    expect(resolveCardHint(0, false)).toBe('none')
    expect(resolveCardHint(2, false)).toBe('choose')
    expect(resolveCardHint(2, true)).toBe('selected')
    expect(resolveCardHint(1, true)).toBe('selected')
  })

  it('有卡未选绝不能被判成"暂无可用水卡"', () => {
    // 这正是修好渲染门之后立刻会踩到的坑：card 为 null 的 v-else 会把未选态说成 none 态
    expect(resolveCardHint(2, false)).not.toBe('none')
    expect(resolveCardHint(5, false)).not.toBe('none')
    expect(resolveCardHint(1, false)).not.toBe('none')
  })

  it('只有 1 张卡却没选中不是 choose，而是 unresolved', () => {
    // choose 的判据必须与模板选卡列表的 usableCards.length > 1 同源，
    // 否则会对单卡用户说"存在多张可用水卡，请先选择"，而页面上一张卡都点不了
    expect(resolveCardHint(1, false)).toBe('unresolved')
  })

  it('choose 只在列表真的会渲染时出现', () => {
    // 逐个卡数验证判据同源：列表门是 count > 1
    for (const count of [1, 2, 3, 10]) {
      const hint = resolveCardHint(count, false)
      expect(hint === 'choose').toBe(count > 1)
    }
  })

  it('卡数为负或异常值按无卡处理（fail-closed）', () => {
    expect(resolveCardHint(-1, false)).toBe('none')
    // hasCard 为真时以已选为准：卡数统计异常不该把已选中的卡说没
    expect(resolveCardHint(0, true)).toBe('selected')
  })
})

/**
 * 确认页的源级门禁：纯函数管不到模板与调用接线，无 SFC 测试装置只能对源码断言。
 * 断言一律锚定到可执行构造（属性、调用），不用会命中注释的裸关键字。
 */
describe('s2-r 确认页源级门禁', () => {
  const source = fs.readFileSync(
    path.join(path.dirname(fileURLToPath(import.meta.url)), 'confirm.vue'),
    'utf8',
  )
  const gates = [...source.matchAll(/v-else-if="([^"]*)"/g)].map(match => match[1])

  /** 按大括号配平截出函数体：用 indexOf('\n}\n') 会被函数内部同形收尾提前截断，半截函数断言会假绿/假红。 */
  function bodyOf(signature: string): string {
    const start = source.indexOf(signature)
    expect(start, `源码里找不到 ${signature}`).toBeGreaterThanOrEqual(0)
    let depth = 0
    for (let i = source.indexOf('{', start); i < source.length; i++) {
      if (source[i] === '{') {
        depth++
      }
      else if (source[i] === '}') {
        depth--
        if (depth === 0) {
          return source.slice(start, i + 1)
        }
      }
    }
    throw new Error(`${signature} 的函数体大括号不配平`)
  }

  it('内容区的门只看 context，不得再叠 eligibility', () => {
    expect(gates).toContain('context')
    // 叠上 eligibility，多卡用户（选卡列表本身就在门内）就永远拿不到触发预检的入口
    expect(gates.filter(gate => gate.includes('eligibility'))).toEqual([])
  })

  it('提交闸走 canSubmitWater 且传入 hasEligibility 与 switchingCard', () => {
    expect(source).toMatch(/canSubmit\s*=\s*computed\(\(\)\s*=>\s*canSubmitWater\(\{/)
    expect(source).toMatch(/hasEligibility:\s*!!eligibility\.value/)
    expect(source).toMatch(/switchingCard:\s*switching\.value/)
    // 双保险：canSubmit 之外 handleSubmit 自己也挡一次
    expect(source).toMatch(/if \(!canSubmit\.value[^)]*switching\.value/)
  })

  it('applyCard 必须把失败抛给调用方，不得自己吞', () => {
    // 加载期（单卡自动选中）失败必须落错误页——那时页面上没有可点的卡，吞掉就没有重试入口
    expect(bodyOf('async function applyCard')).not.toMatch(/\bcatch\b/)
    expect(source).toMatch(/if \(selection\.mode === 'auto'\) \{\s*await applyCard\(/)
  })

  it('用户点选走 pickCard，且它兜住报价失效', () => {
    const pickCard = bodyOf('async function pickCard')
    expect(pickCard).toMatch(/await applyCard\(cardId\)/)
    expect(pickCard).toMatch(/catch\s*\(error\)/)
    expect(pickCard).toMatch(/isQuoteInvalid\(error\)/)
    expect(pickCard).toMatch(/markQuoteInvalid\(error\)/)
    // 模板上的选卡入口必须挂 pickCard 而不是会抛的 applyCard
    expect(source).toMatch(/@click="!switching && pickCard\(item\.cardId\)"/)
  })

  it('水卡区四态文案各自有门，none 与 unresolved 不混用', () => {
    expect(source).toMatch(/v-if="cardHint === 'choose'"/)
    expect(source).toMatch(/v-else-if="cardHint === 'none'"/)
    expect(source).toMatch(/v-else-if="cardHint === 'unresolved'"/)
    // "暂无可用水卡"只能挂在 none 门上，不许出现在 unresolved 门上
    const noneBranch = source.slice(source.indexOf(`v-else-if="cardHint === 'none'"`))
    expect(noneBranch.slice(0, noneBranch.indexOf('</view>'))).toMatch(/暂无可用水卡/)
    const unresolvedBranch = source.slice(source.indexOf(`v-else-if="cardHint === 'unresolved'"`))
    expect(unresolvedBranch.slice(0, unresolvedBranch.indexOf('</view>'))).not.toMatch(/暂无可用水卡/)
  })
})
