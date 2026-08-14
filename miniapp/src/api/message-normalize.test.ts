import { describe, expect, it } from 'vitest'
import { ContractError } from './common'
import { DOMAIN_BY_VALUE, normalizeRealMessage, VALUE_BY_DOMAIN } from './message'

/**
 * E2E-07 包A/包C 契约测试：字典 1312 双向映射同源 + real 归一化口径。
 * 这里锁定的是「服务端 tinyint ↔ 前端字符串枚举」的翻译层——翻译错一档，
 * 域 Tab 筛选就会把消息挂错类目。
 */
describe('message dictionary parity (1312)', () => {
  it('双向映射互逆且覆盖全部六域', () => {
    expect(Object.keys(VALUE_BY_DOMAIN)).toHaveLength(6)
    for (const [domain, value] of Object.entries(VALUE_BY_DOMAIN)) {
      expect(DOMAIN_BY_VALUE[value]).toBe(domain)
    }
    // 与 02-ws-business.sql 字典 1312 逐值同源：1取水 2卡券 3配送 4机主 5系统 6商城
    expect(DOMAIN_BY_VALUE[1]).toBe('water')
    expect(DOMAIN_BY_VALUE[2]).toBe('card')
    expect(DOMAIN_BY_VALUE[3]).toBe('delivery')
    expect(DOMAIN_BY_VALUE[4]).toBe('owner')
    expect(DOMAIN_BY_VALUE[5]).toBe('system')
    expect(DOMAIN_BY_VALUE[6]).toBe('mall')
  })
})

describe('normalizeRealMessage', () => {
  const base = {
    messageId: '9701',
    msgDomain: 4,
    msgTitle: '报修申请已受理',
    summary: '您的申请「饮水机漏水」已受理',
    msgContent: '您的申请「饮水机漏水」已受理（工单号 WO-1），我们将尽快安排处理。',
    msgChannel: 1,
    sendStatus: 4,
    sendTime: '20260731120000',
    readFlag: 0,
    objectType: 'service',
    objectId: 'REQ-ACC-1',
  }

  it('合法行归一：域/渠道/未读全对齐 real 口径', () => {
    const item = normalizeRealMessage(base, 'acc-9001')
    expect(item.domain).toBe('owner')
    expect(item.channel).toBe('in-app')
    expect(item.unread).toBe(true)
    expect(item.objectAccess).toBe('allowed')
    expect(item.accountId).toBe('acc-9001')
    expect(item.objectType).toBe('service')
  })

  it('全局 Long→字符串序列化：数字字段以字符串到达同样归一', () => {
    const item = normalizeRealMessage(
      { ...base, msgDomain: '3', sendStatus: '4', readFlag: '1', msgChannel: '2' },
      'acc-9001',
    )
    expect(item.domain).toBe('delivery')
    expect(item.unread).toBe(false)
    expect(item.channel).toBe('wechat-subscribe')
  })

  it('未知域 / 非法发送状态 / 缺标题：契约不符直接拒绝，不产出残缺行', () => {
    // 6=商城（字典 1312 同源）。这条曾经断言「域 6 必须抛」——把当时的缺陷当成了契约：
    // 服务端一开始发商城站内信，消息中心就对所有收到过它的用户整页 fail-closed。
    expect(normalizeRealMessage({ ...base, msgDomain: 6 }, '').domain).toBe('mall')
    expect(() => normalizeRealMessage({ ...base, msgDomain: 7 }, '')).toThrow(ContractError)
    expect(() => normalizeRealMessage({ ...base, sendStatus: 5 }, '')).toThrow(ContractError)
    expect(() => normalizeRealMessage({ ...base, msgTitle: undefined }, '')).toThrow(ContractError)
  })

  it('未知 objectType 收敛为 undefined（详情页不给来路不明的跳转）', () => {
    const item = normalizeRealMessage({ ...base, objectType: 'alarm' }, '')
    expect(item.objectType).toBeUndefined()
  })
})
