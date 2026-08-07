import { describe, expect, it } from 'vitest'
import { ownerTransactionPeriodQuery } from './device'

describe('ownerTransactionPeriodQuery', () => {
  const authoritativePeriod = {
    periodStart: '20260728000000',
    periodEnd: '20260803113000',
  } as const

  it('近 7 日复用服务端权威闭区间', () => {
    expect(ownerTransactionPeriodQuery('7d', authoritativePeriod)).toEqual(authoritativePeriod)
  })

  it('全部档不发送时间哨兵或隐含窗口', () => {
    expect(ownerTransactionPeriodQuery('', authoritativePeriod)).toEqual({})
  })

  it('近 7 日缺少权威周期时 fail-closed', () => {
    expect(() => ownerTransactionPeriodQuery('7d')).toThrow('经营统计周期缺失')
  })
})
