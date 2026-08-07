import { describe, expect, it } from 'vitest'
import { noticeTextOf } from './runtime-notice'

/**
 * 顶部提示条按域取文案。
 *
 * <p>本文件原有 20 余条用例钉的是「mock/real 两套文案的分流判定」。该机制已于
 * 2026-08-06 整体拆除——mock 基建 2026-08-02 退役，三份环境档每个业务域都是 real，
 * 分流分支在任何出货构建里都不可达。留着测试等于给死代码上锁。</p>
 *
 * <p>现在钉两件仍然重要的事：登记过的域必须有文案（card 曾因漏登记而让用户把
 * 真实生效的成员授权当成演示随手点），以及文案里不许再出现实现术语。</p>
 */
describe('顶部提示条文案', () => {
  it('登记过的域返回文案', () => {
    expect(noticeTextOf('card')).toBe('授权后对方可用你的卡取水，有单日限额。')
    expect(noticeTextOf('delivery')).toBe('操作提交后无法撤销。')
    expect(noticeTextOf('device')).toBe('报修提交后无法撤销。')
    expect(noticeTextOf('message')).toBe('不会发微信通知，需在此查看。')
  })

  it('card 域必须有文案：成员授权是真写库的，被授权人能真刷卡扣余额', () => {
    expect(noticeTextOf('card')).toBeTruthy()
    expect(noticeTextOf('card')).toContain('限额')
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
    for (const domain of ['card', 'delivery', 'device', 'message'] as const) {
      const text = noticeTextOf(domain) ?? ''
      for (const word of banned) {
        expect(text, `${domain} 文案含「${word}」`).not.toContain(word)
      }
      // 提示条一句话说完；超过就说明又在讲系统了
      expect(text.length, `${domain} 文案过长`).toBeLessThanOrEqual(25)
    }
  })
})
