import { describe, expect, it } from 'vitest'
import type { AppRouteRecord } from '@/types/router'
import { PRIMARY_BUSINESS_MENU_CONTRACTS } from '@/config/businessNavigation'
import {
  applyMenuNavigationPolicy,
  projectDisplayMenu,
  resolveDisplayMenuActivePath
} from './MenuDisplayProjector'

const ORDER_PAGES = [
  '/order/index',
  '/order/delivery',
  '/order/appeal',
  '/order/split',
  '/order/reconcile',
  '/order/splitconfig',
  '/order/autorule',
  '/order/waterstats'
] as const

function orderRoot(authorizedPaths: readonly string[] = ORDER_PAGES): AppRouteRecord {
  return {
    path: '/order',
    name: 'OrderRoot',
    component: '/index/index',
    meta: { title: '订单中心', icon: 'ri:file-list-3-line' },
    children: authorizedPaths.map((path) => ({
      path,
      name: path.split('/').at(-1),
      component: path,
      meta: { title: path }
    }))
  }
}

function routeByPath(root: AppRouteRecord, path: string): AppRouteRecord | undefined {
  if (root.path === path) return root
  for (const child of root.children ?? []) {
    const found = routeByPath(child, path)
    if (found) return found
  }
  return undefined
}

describe('PC 职责工作区导航投影', () => {
  it('将同一授权根稳定投影为订单、财务、配送运营三个一级入口', () => {
    const prepared = applyMenuNavigationPolicy([orderRoot()])
    const display = projectDisplayMenu(prepared)

    expect(display.map((item) => [item.meta.title, item.path])).toEqual([
      ['订单中心', '/order/index'],
      ['财务结算', '/order/split'],
      ['配送运营', '/order/autorule']
    ])
    expect(new Set(display.map((item) => item.name)).size).toBe(3)
  })

  it('部分授权角色只看到拥有页面的工作区，并以组内首个授权页为入口', () => {
    const prepared = applyMenuNavigationPolicy([orderRoot(['/order/reconcile'])])
    const display = projectDisplayMenu(prepared)

    expect(prepared[0].redirect).toBe('/order/reconcile')
    expect(display.map((item) => [item.meta.title, item.path])).toEqual([
      ['财务结算', '/order/reconcile']
    ])
  })

  it('旧深链映射到正确工作区，不被共同的 /order 前缀误点亮订单中心', () => {
    const display = projectDisplayMenu(applyMenuNavigationPolicy([orderRoot()]))

    expect(resolveDisplayMenuActivePath('/order/splitconfig', display)).toBe('/order/split')
    expect(resolveDisplayMenuActivePath('/order/waterstats', display)).toBe('/order/autorule')
    expect(resolveDisplayMenuActivePath('/order/appeal', display)).toBe('/order/index')
  })

  it('完整路由树保留旧地址，只按职责写入各自一级激活路径', () => {
    const [prepared] = applyMenuNavigationPolicy([orderRoot()])

    expect(prepared.redirect).toBe('/order/index')
    expect(routeByPath(prepared, '/order/reconcile')?.meta.activePath).toBe('/order/split')
    expect(routeByPath(prepared, '/order/waterstats')?.meta.activePath).toBe('/order/autorule')
    expect(routeByPath(prepared, '/order/delivery')?.meta.activePath).toBe('/order/index')
  })

  it('每个精确页面只归属一个工作区，防止以后复制配置造成双高亮', () => {
    const ownedPaths = PRIMARY_BUSINESS_MENU_CONTRACTS.flatMap((item) => item.ownedPaths ?? [])
    expect(new Set(ownedPaths).size).toBe(ownedPaths.length)

    for (const contract of PRIMARY_BUSINESS_MENU_CONTRACTS.filter(
      (item) => item.ownedPaths?.length
    )) {
      expect(contract.ownedPaths).toContain(contract.defaultPath)
    }
  })
})
