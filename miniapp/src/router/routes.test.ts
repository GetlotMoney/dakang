import { describe, expect, it } from 'vitest'
import { evaluateRouteAccess } from './guard'
import { appRoutes, buildRouteUrl, findRouteById } from './routes'

describe('miniapp route contracts', () => {
  it('contains 29 unique routes and exactly three fixed tab pages', () => {
    // E2E-08 增 O06 收益钱包：28→29
    expect(appRoutes).toHaveLength(29)
    expect(new Set(appRoutes.map(route => route.id)).size).toBe(29)
    expect(new Set(appRoutes.map(route => route.path)).size).toBe(29)

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
    expect(buildRouteUrl('U06', { orderNo: 'MR-1', source: 'local-mock' })).toBe(
      '/pages/user/order/detail?orderNo=MR-1&source=local-mock',
    )
    expect(() => buildRouteUrl('U06', { orderNo: 'MR-1', source: 'unknown' })).toThrowError(
      '页面参数不合法',
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
