import { describe, expect, it } from 'vitest'
import { noticeTextOf } from './runtime-notice'

/**
 * 顶部提示条按域取文案。钉两件事：登记过的域必须有文案（card 漏登记会让用户把真实生效的
 * 成员授权当演示随手点），以及文案里不许出现实现术语。
 */
describe('顶部提示条文案', () => {
  it('登记过的域返回文案', () => {
    expect(noticeTextOf('card')).toBe('授权后对方可用你的卡取水。')
    expect(noticeTextOf('device')).toBe('报修提交后无法撤销。')
    expect(noticeTextOf('message')).toBe('不会发微信通知，需在此查看。')
  })

  /** delivery 域登记已摘除（各动作事实写在 message.confirm 里）；断言守住「不许加回登记表」。 */
  it('delivery 域不再登记：事实由各动作自己的二次确认承担', () => {
    expect(noticeTextOf('delivery')).toBeUndefined()
  })

  /**
   * 2026-08-10：断言从「必须含『限额』」改为「必须含『取水』」。
   * 原文「授权后对方可用你的卡取水，有单日限额。」的后半句只是把表单里
   * 「单日限额」那一栏的字段名复述一遍——限额设不设、设多少，那一栏自己会说。
   * 这条要守住的从来不是「限额」两个字，而是「对方能真刷你的卡扣余额」这个事实，
   * 它正是当年漏登记时用户把真实授权当成演示随手点的原因。
   */
  it('card 域必须有文案：成员授权是真写库的，被授权人能真刷卡取水', () => {
    expect(noticeTextOf('card')).toBeTruthy()
    expect(noticeTextOf('card')).toContain('取水')
  })

  it('未登记的域与未传域一律返回 undefined，由调用方整条不渲染', () => {
    expect(noticeTextOf('order')).toBeUndefined()
    expect(noticeTextOf('recharge')).toBeUndefined()
    expect(noticeTextOf('auth')).toBeUndefined()
    expect(noticeTextOf(undefined)).toBeUndefined()
  })

  it('文案里不出现实现术语与口语旁白', () => {
    const banned = [
      'mock',
      'Mock',
      '原型',
      '快照',
      '契约',
      '口径',
      '受控媒体',
      '服务端',
      '后端',
      '接口',
      'Pay-Sim',
      '演示数据',
      '会真的',
    ]
    for (const domain of ['card', 'device', 'message'] as const) {
      const text = noticeTextOf(domain) ?? ''
      for (const word of banned) {
        expect(text, `${domain} 文案含「${word}」`).not.toContain(word)
      }
      // 提示条一句话说完；超过就说明又在讲系统了
      expect(text.length, `${domain} 文案过长`).toBeLessThanOrEqual(25)
    }
  })
})
