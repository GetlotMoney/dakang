import { describe, expect, it } from 'vitest'
import { evaluateRouteAccess } from './guard'
import { appRoutes, buildRouteUrl, findRouteById } from './routes'
import { initialScenario } from '@/scenario/fixtures'

describe('miniapp route contracts', () => {
  it('contains 28 unique routes and exactly three fixed tab pages', () => {
    expect(appRoutes).toHaveLength(28)
    expect(new Set(appRoutes.map(route => route.id)).size).toBe(28)
    expect(new Set(appRoutes.map(route => route.path)).size).toBe(28)

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

  it('validates required and enumerated route parameters', () => {
    expect(() => buildRouteUrl('U06')).toThrowError(/orderNo/)
    expect(() => buildRouteUrl('U06', { orderNo: 'WO-1', focus: 'unknown' })).toThrowError(
      /focus/,
    )
    expect(buildRouteUrl('U06', { orderNo: 'WO-1', focus: 'delivery' })).toBe(
      '/pages/user/order/detail?orderNo=WO-1&focus=delivery',
    )
    expect(buildRouteUrl('U06', { orderNo: 'MR-1', source: 'local-mock' })).toBe(
      '/pages/user/order/detail?orderNo=MR-1&source=local-mock',
    )
    expect(() => buildRouteUrl('U06', { orderNo: 'MR-1', source: 'unknown' })).toThrowError(
      /source/,
    )
  })

  it('denies direct courier access for a base user', () => {
    const userContext = initialScenario.accounts.find(
      account => account.accountId === 'ACCOUNT-USER-001',
    )!

    expect(evaluateRouteAccess('/pages/courier/task/index', userContext)).toMatchObject({
      allowed: false,
      code: 'CAPABILITY_DENIED',
    })
  })
})
