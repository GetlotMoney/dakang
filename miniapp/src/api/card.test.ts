import { describe, expect, it } from 'vitest'
import { normalizeCardDetail } from './card'

/**
 * L2-READ 卡详情归一化：字段缺失时必须落到 fail-closed 一侧，
 * 绝不因后端少给字段就在前端显示成"可用/全场通用"。
 */
describe('normalizeCardDetail', () => {
  const base = {
    cardId: '100',
    cardNo: 'DK-CARD-001',
    cardType: 1,
    cardStatus: 1,
    balanceFen: '5500',
    balanceMl: '470120',
  }

  it('scopeDescription 缺失/为 null 时按「未配置（默认拒绝）」呈现', () => {
    expect(normalizeCardDetail({ ...base } as never).scopeDescription).toBe('未配置（默认拒绝）')
    expect(normalizeCardDetail({ ...base, scopeDescription: null } as never).scopeDescription)
      .toBe('未配置（默认拒绝）')
  })

  it('服务端给出的范围描述原样透传，不在前端二次解释', () => {
    expect(normalizeCardDetail({ ...base, scopeDescription: '全场通用' } as never).scopeDescription)
      .toBe('全场通用')
  })

  it('长整型 ID 收敛为字符串，金额/水量转 number', () => {
    const d = normalizeCardDetail({ ...base } as never)
    expect(d.cardId).toBe('100')
    expect(d.balanceFen).toBe(5500)
    expect(d.balanceMl).toBe(470120)
  })

  it('members 缺失时为空数组；enabled 仅在显式 true 时为真', () => {
    expect(normalizeCardDetail({ ...base } as never).members).toEqual([])
    const members = normalizeCardDetail({
      ...base,
      members: [
        { memberId: '1', memberUserId: '21', memberName: '爸爸', maskedPhone: '138****5678', enabled: true },
        { memberId: '2', memberUserId: '22', memberName: '阿姨', maskedPhone: '', enabled: false },
        { memberId: '3', memberUserId: '23', memberName: '缺字段' },
      ],
    } as never).members
    expect(members).toHaveLength(3)
    expect(members[0].enabled).toBe(true)
    expect(members[1].enabled).toBe(false)
    // enabled 字段缺失一律按未生效处理（fail-closed）
    expect(members[2].enabled).toBe(false)
    expect(members[2].maskedPhone).toBe('')
  })

  it('前端绝不拼造手机号：只透传服务端脱敏值', () => {
    const members = normalizeCardDetail({
      ...base,
      members: [{ memberId: '1', memberUserId: '21', memberName: 'x', enabled: true }],
    } as never).members
    expect(members[0].maskedPhone).toBe('')
  })
})
