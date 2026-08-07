import { describe, expect, it } from 'vitest'
import { maskPhone, pendingSplitLineText } from './format'

describe('maskPhone', () => {
  it('11 位号码保留前 3 后 4', () => {
    expect(maskPhone('13900001111')).toBe('139****1111')
  })

  it('未绑号（undefined/空串）返回空串，不抛错', () => {
    // 仅微信身份建号的账号没有号码；旧写法直接 slice 会在首页白屏。
    expect(maskPhone(undefined)).toBe('')
    expect(maskPhone('')).toBe('')
  })

  it('长度异常一律返回空串，不吐半截号码', () => {
    expect(maskPhone('139')).toBe('')
    expect(maskPhone('139000011112222')).toBe('')
  })
})

/**
 * D-421 R1 渲染回归：钱包在途分润行。展示逻辑收口在 pendingSplitLineText，
 * 页面模板只做「null 则整行不渲染」——文案形状与出行条件由这里钉死。
 */
describe('pendingSplitLineText', () => {
  it('有在途且解冻时间合法：金额+最早入账时间', () => {
    expect(pendingSplitLineText(700, '20260808120000'))
      .toBe('在途分润 ¥7.00，最早 08-08 12:00 起入账')
  })

  it('无在途（0/负数）整行不渲染：返回 null', () => {
    expect(pendingSplitLineText(0)).toBeNull()
    expect(pendingSplitLineText(0, '20260808120000')).toBeNull()
    expect(pendingSplitLineText(-1, '20260808120000')).toBeNull()
  })

  it('解冻时间缺省或非法：只报金额，不出现「最早 — 起入账」半截话', () => {
    expect(pendingSplitLineText(700)).toBe('在途分润 ¥7.00')
    expect(pendingSplitLineText(700, '')).toBe('在途分润 ¥7.00')
    expect(pendingSplitLineText(700, '2026-08-08')).toBe('在途分润 ¥7.00')
  })
})
