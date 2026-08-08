import { describe, expect, it } from 'vitest'
import { normalizeGiftUserId } from './gift-issue-form'

/**
 * 赠卡链收卡用户 ID string 守恒：>2^53 的相邻奇数 ID 经 Number() 会塌缩为同一偶数，
 * 礼包会发给错误用户——本链必须逐字直传，损坏输入 fail-closed。
 */
describe('gift-issue-form 收卡用户 ID string 直传', () => {
  const HUGE_A = '9007199254740993'
  const HUGE_B = '9007199254740995'

  it('相邻奇数大 ID 逐字直传，序列化后仍逐字（不塌缩为相邻偶数）', () => {
    expect(normalizeGiftUserId(HUGE_A)).toBe(HUGE_A)
    expect(normalizeGiftUserId(HUGE_B)).toBe(HUGE_B)
    // 反证：数值化即失真——两个奇数 ID 各自被舍入成相邻偶数（993→992、995→996），
    // 发卡对象就换了人；string 直传是唯一无损通道
    expect(String(Number(HUGE_A))).not.toBe(HUGE_A)
    expect(String(Number(HUGE_B))).not.toBe(HUGE_B)
    const body = JSON.stringify({ userId: normalizeGiftUserId(HUGE_A) })
    expect(body).toContain(`"userId":"${HUGE_A}"`)
    expect(body).not.toContain('9007199254740992')
  })

  it('文本输入去首尾空白后逐字保留', () => {
    expect(normalizeGiftUserId(`  ${HUGE_A}  `)).toBe(HUGE_A)
    expect(normalizeGiftUserId('7')).toBe('7')
  })

  it('损坏输入 fail-closed：前导零/零/负数/小数/科学计数/超 Long/超界 number 全拒', () => {
    expect(normalizeGiftUserId('007')).toBeNull()
    expect(normalizeGiftUserId('0')).toBeNull()
    expect(normalizeGiftUserId('-5')).toBeNull()
    expect(normalizeGiftUserId('3.5')).toBeNull()
    expect(normalizeGiftUserId('1e3')).toBeNull()
    expect(normalizeGiftUserId('9223372036854775808')).toBeNull()
    expect(normalizeGiftUserId('')).toBeNull()
    expect(normalizeGiftUserId(undefined)).toBeNull()
    // JSON/输入侧已丢精度的 number：转字符串只会固化错值，必须拒绝
    expect(normalizeGiftUserId(Number.MAX_SAFE_INTEGER + 2)).toBeNull()
    // 安全范围内正整数 number 是合法历史来源
    expect(normalizeGiftUserId(7)).toBe('7')
  })
})
