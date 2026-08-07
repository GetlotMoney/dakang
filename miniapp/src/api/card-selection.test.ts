import { describe, expect, it } from 'vitest'
import type { UsableCard } from './card'
import { canShowRechargeEntry, resolveCardSelection } from './card'

/**
 * U04 取水卡选择与成员卡权限（CARD-MEMBER）。
 *
 * 这组判定决定「钱从谁的卡里扣」与「谁能给卡充值」：
 * 多卡静默默认第一张会让成员在不知情时扣卡主的卡；
 * MEMBER 卡出现充值入口则违反「成员只能取水」的授权边界。
 */
describe('u04 卡选择判定', () => {
  const cardOf = (id: string, role: 'OWNER' | 'MEMBER'): UsableCard => ({
    cardId: id,
    cardNo: `VC-${id}`,
    cardType: 1,
    userId: role === 'OWNER' ? '9' : '1',
    balanceFen: 5500,
    balanceMl: 470120,
    expireTime: undefined,
    cardStatus: 1,
    accessRole: role,
    canRecharge: role === 'OWNER',
    canManageMembers: role === 'OWNER',
    canMergeToPaidCard: false,
    remainingDailyLimitMl: role === 'MEMBER' ? 6000 : undefined,
  } as UsableCard)

  it('无卡 → none（由服务端 CARD_MISSING 阻断，前端不造文案）', () => {
    expect(resolveCardSelection([]).mode).toBe('none')
  })

  it('恰一张 → 自动选中该卡', () => {
    const only = cardOf('1', 'OWNER')
    const r = resolveCardSelection([only])
    expect(r.mode).toBe('auto')
    expect(r.mode === 'auto' && r.selected.cardId).toBe('1')
  })

  // 多卡必须用户选：静默选第一张=成员可能不知情扣了卡主的卡
  it('多张 → 必须用户显式选择，不得静默默认', () => {
    const r = resolveCardSelection([cardOf('1', 'OWNER'), cardOf('2', 'MEMBER')])
    expect(r.mode).toBe('choose')
    expect(r.mode === 'choose' && r.candidates.length).toBe(2)
  })

  it('成员卡不得出现充值入口；本人卡按 canRecharge', () => {
    expect(canShowRechargeEntry(cardOf('2', 'MEMBER'))).toBe(false)
    expect(canShowRechargeEntry(cardOf('1', 'OWNER'))).toBe(true)
    // 服务端明确 canRecharge=false 的 OWNER 卡（如注销中）同样隐藏——前端不越权放宽
    expect(canShowRechargeEntry({ accessRole: 'OWNER', canRecharge: false })).toBe(false)
  })
})
