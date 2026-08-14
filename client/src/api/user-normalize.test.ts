import { describe, expect, it } from 'vitest'
import {
  userAuditRowOf,
  userDelta,
  userFlowRowOf,
  userOrderRowOf,
  userPage,
  userRelationItemOf,
  userRelationOf
} from './user-normalize'

/**
 * 用户档案响应归一化判据。
 *
 * 夹具按**真实响应形态**书写：服务端对 Long/long 全局 ToStringSerializer，
 * 所以 total、金额（分）、水量（毫升）、计数在线上都是十进制字符串，
 * 而状态、类型、来源端这些 Integer 字段才是 number。照着 TS 类型写成 number 的夹具
 * 会让测试永远绿、而线上永远走另一条分支。
 */
describe('用户档案分页壳', () => {
  it('total 的字符串形态归一为数字', () => {
    const page = userPage<{ id: string }>({ total: '37', list: [{ id: '7' }] }, (row) => ({
      id: String(row.id)
    }))
    expect(page.total).toBe(37)
    expect(page.list).toEqual([{ id: '7' }])
  })

  it('响应缺失或 total 畸形一律抛错，不退化成空表', () => {
    expect(() => userPage(null, (row) => row)).toThrow()
    expect(() => userPage({ total: '-1', list: [] }, (row) => row)).toThrow()
    expect(() => userPage({ total: 'abc', list: [] }, (row) => row)).toThrow()
  })

  // 下面三条守的是 list 侧：total 正常而 list 出问题时，页面会显示「共 37 条」配一张
  // 空表或缺行的表，全程不报错，运营据此得出「这个人没有流水」——比整页失败危险得多。
  it('list 缺失或不是数组一律抛错，不与「一条都没有」混为一谈', () => {
    expect(() => userPage({ total: '37' }, (row) => row)).toThrow('list')
    expect(() => userPage({ total: '37', list: null }, (row) => row)).toThrow('list')
    expect(() => userPage({ total: '37', list: 'x' }, (row) => row)).toThrow('list')
    // 合法边界：一条都没有时 list 是空数组，total 为 0，不该抛
    expect(userPage({ total: '0', list: [] }, (row) => row)).toEqual({ total: 0, list: [] })
  })

  it('非对象行抛错，不静默丢行凑成一张缺行的表', () => {
    expect(() => userPage({ total: '2', list: [1, 2] }, (row) => row)).toThrow('行')
    expect(() => userPage({ total: '2', list: [{ id: '1' }, null] }, (row) => row)).toThrow('行')
    expect(() => userPage({ total: '1', list: [['id', '1']] }, (row) => row)).toThrow('行')
  })

  it('行内归一化抛错时整页失败，异常必须透传而不是被逐行吞掉', () => {
    const good = {
      id: '1',
      cardId: '2',
      flowType: 2,
      amountChange: '-500',
      mlChange: '0',
      amountAfter: '1500',
      mlAfter: '20000'
    }
    expect(() =>
      userPage({ total: '2', list: [good, { ...good, amountChange: '5.5' }] }, userFlowRowOf)
    ).toThrow('amountChange')
  })
})

describe('资金流水行', () => {
  const raw = {
    id: '9007199254740993',
    cardId: '9007199254740995',
    cardNo: 'GC0001',
    flowType: 2,
    amountChange: '-500',
    mlChange: '0',
    amountAfter: '1500',
    mlAfter: '20000',
    orderId: '9007199254740997',
    createTime: '20260813101500'
  }

  it('金额与水量四列由字符串归一为数字，正负保留', () => {
    const row = userFlowRowOf(raw)
    expect(row.amountChange).toBe(-500)
    expect(row.mlChange).toBe(0)
    expect(row.amountAfter).toBe(1500)
    expect(row.mlAfter).toBe(20000)
  })

  it('身份类 Long ID 逐字保留字符串，绝不数值化', () => {
    const row = userFlowRowOf(raw)
    // 这三个编号都超过 2^53：一旦被转成 number 就会落到相邻值上，
    // 页面上看的是这一条、点进去的是另一条，且全程不报错。
    expect(row.id).toBe('9007199254740993')
    expect(row.cardId).toBe('9007199254740995')
    expect(row.orderId).toBe('9007199254740997')
    expect(typeof row.id).toBe('string')
  })

  it('畸形金额抛错，不静默变成 0', () => {
    expect(() => userFlowRowOf({ ...raw, amountChange: '' })).toThrow()
    expect(() => userFlowRowOf({ ...raw, amountChange: '5.5' })).toThrow()
    expect(() => userFlowRowOf({ ...raw, mlAfter: 'abc' })).toThrow()
    // 超过安全整数范围的金额同样拒绝：静默丢精度的金额比报错危险得多
    expect(() => userFlowRowOf({ ...raw, amountAfter: '9007199254740993' })).toThrow()
  })

  it('带符号解析允许负的变动后快照，让异常显示出来而不是整页打不开', () => {
    expect(userDelta('-1', 'amountAfter')).toBe(-1)
  })
})

describe('订单行', () => {
  it('金额与水量归一，缺省列保持 undefined 而不是 0', () => {
    const row = userOrderRowOf({
      id: '9007199254740993',
      orderNo: 'WD202608130001',
      orderType: 1,
      orderStatus: 4,
      orderAmount: '2400',
      planMl: '19000',
      actualMl: null,
      createTime: '20260813101500'
    })
    expect(row.id).toBe('9007199254740993')
    expect(row.orderAmount).toBe(2400)
    expect(row.planMl).toBe(19000)
    // 设备未回传实际水量时是"没有这个事实"，不是"实际出水 0 毫升"
    expect(row.actualMl).toBeUndefined()
  })
})

describe('关系归属', () => {
  it('直接下级计数归一，上级编号保持字符串', () => {
    const relation = userRelationOf({
      ownInviteCode: 'IV1A2B3C4D',
      referrerUserId: '9007199254740993',
      referrerUserName: '张三',
      referrerUserPhone: '139****1111',
      referrerMissing: false,
      directInviteeCount: '12'
    })
    expect(relation.directInviteeCount).toBe(12)
    expect(relation.referrerUserId).toBe('9007199254740993')
  })

  it('未绑定上级时不编造关系', () => {
    const relation = userRelationOf({ directInviteeCount: '0' })
    expect(relation.referrerUserId).toBeUndefined()
    expect(relation.directInviteeCount).toBe(0)
  })

  it('下级行与审计行原样透传，编号不被改写', () => {
    expect(userRelationItemOf({ id: '9007199254740993', userName: '李四' }).id).toBe(
      '9007199254740993'
    )
    expect(userAuditRowOf({ id: '9007199254740995', eventTypeName: '订单状态变化' }).id).toBe(
      '9007199254740995'
    )
  })
})
