import { describe, expect, it } from 'vitest'
import { evaluateRouteAccess } from './guard'
import { appRoutes, buildRouteUrl, findRouteById } from './routes'

describe('miniapp route contracts', () => {
  it('contains 41 unique routes and exactly three fixed tab pages', () => {
    // E2E-08 增 O06 收益钱包：28→29；S2 增 U16 自动补货规则：29→30；
    // E2E-09 S1 增 M01 商城首页 + M02 商品详情：30→32；
    // E2E-09 S2 增 M03 购物车 + M04 确认订单 + M05 商城订单 + M06 订单详情：32→36。
    // E2E-09 S3-B 增 M07 商城配送任务 + M08 商城任务详情（配送端，与一期水配送任务分域）：36→38；
    // E2E-09 S4 增 M09 申请售后 + M10 我的售后 + M11 售后详情：38→41。
    // Tabbar 仍固定 home/order/profile 三个，商城四页全部走 navigateTo，不进底栏。
    expect(appRoutes).toHaveLength(41)
    expect(new Set(appRoutes.map(route => route.id)).size).toBe(41)
    expect(new Set(appRoutes.map(route => route.path)).size).toBe(41)

    const tabRoutes = appRoutes.filter(route => 'tab' in route)
    expect(tabRoutes.map(route => route.tab)).toEqual(['home', 'order', 'profile'])
    expect(tabRoutes.map(route => route.id)).toEqual(['U01', 'U02', 'U03'])
  })

  it('keeps courier and owner pages behind their matching capabilities', () => {
    expect(findRouteById('D01').requiredCapability).toBe('COURIER_WORK')
    expect(findRouteById('D02').requiredCapability).toBe('COURIER_APPLY')
    expect(findRouteById('D04').requiredCapability).toBe('COURIER_WORK')
    expect(findRouteById('O01').requiredCapability).toBe('OWNER_VIEW')
    expect(findRouteById('O03').requiredCapability).toBe('OWNER_VIEW')
    expect(findRouteById('O05').requiredCapability).toBe('OWNER_SERVICE')
  })

  it('opens the income wallet to every signed-in user, not just owners', () => {
    // 分账收款方含配送员（配送线 30%）与预留的推荐人/区域服务商：绑 OWNER_VIEW
    // 会让拿了分润的配送员在小程序里看不到自己的钱（主环境实点抓获的设计缺陷）
    expect(findRouteById('O06').requiredCapability).toBe('USER_BASE')
    // 回退目标必须是非机主也能进的页面，否则用户被困在钱包页
    expect(findRouteById('O06').defaultBackTo).toBe('U03')
    expect(findRouteById('U03').requiredCapability).toBe('USER_BASE')
  })

  it('validates required and enumerated route parameters', () => {
    // 拒绝文案会被守卫直接弹给用户，故只断言面向用户的说法：
    // 页面编号与参数名属于排障线索，靠错误码 ROUTE_PARAMS_INVALID 与抛出点定位
    expect(() => buildRouteUrl('U06')).toThrowError('页面参数不完整')
    expect(() => buildRouteUrl('U06', { orderNo: 'WO-1', focus: 'unknown' })).toThrowError(
      '页面参数不合法',
    )
    expect(buildRouteUrl('U06', { orderNo: 'WO-1', focus: 'delivery' })).toBe(
      '/pages/user/order/detail?orderNo=WO-1&focus=delivery',
    )
    // source 参数随 mock 分流一并删除：白名单里没有它了，传进来即拒绝。
    expect(() => buildRouteUrl('U06', { orderNo: 'MR-1', source: 'local-mock' })).toThrowError(
      '页面参数不合法',
    )
    // 结算行必须随页面参数传递：缺 lines 直接拒绝，绝不打开一张不知道在买什么的结算页
    expect(() => buildRouteUrl('M04')).toThrowError('页面参数不完整')
    expect(buildRouteUrl('M04', { lines: '12:2,13:1' })).toBe(
      '/pages/mall/checkout?lines=12%3A2%2C13%3A1',
    )
  })

  it('denies direct courier access for a base user', () => {
    const userContext = {
      accountId: 'ACCOUNT-USER-001',
      userId: '1',
      userName: '张女士',
      userPhone: '13900001111',
      phoneBound: true,
      capabilities: ['USER_BASE', 'COURIER_APPLY'] as ('USER_BASE' | 'COURIER_APPLY')[],
    }

    expect(evaluateRouteAccess('/pages/courier/task/index', userContext)).toMatchObject({
      allowed: false,
      code: 'CAPABILITY_DENIED',
    })
  })
})
