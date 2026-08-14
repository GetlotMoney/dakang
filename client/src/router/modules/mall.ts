import { AppRouteRecord } from '@/types/router'

/**
 * 商城管理路由（二期 E2E-09 S1）
 * 运行时为后端菜单模式（VITE_ACCESS_MODE=backend），菜单以 api_rbac_menu 为准；
 * 本文件用于前端路由模式，并与数据库菜单保持一致；两处变更必须同步。
 */
export const mallRoutes: AppRouteRecord = {
  path: '/mall',
  name: 'MallRoot',
  component: '/index/index',
  meta: {
    title: 'menus.mall.title',
    icon: 'ri:store-2-line',
    roles: ['R_SUPER', 'R_ADMIN']
  },
  children: [
    {
      path: 'product',
      name: 'MallProduct',
      component: '/mall/product',
      meta: {
        title: 'menus.mall.product',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'category',
      name: 'MallCategory',
      component: '/mall/category',
      meta: {
        title: 'menus.mall.category',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'warehouse',
      name: 'MallWarehouse',
      component: '/mall/warehouse',
      meta: {
        title: 'menus.mall.warehouse',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'stock',
      name: 'MallStock',
      component: '/mall/stock',
      meta: {
        title: 'menus.mall.stock',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'aftersale',
      name: 'MallAfterSale',
      component: '/mall/aftersale',
      meta: {
        title: 'menus.mall.aftersale',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'fulfillment',
      name: 'MallFulfillment',
      component: '/mall/fulfillment',
      meta: {
        title: 'menus.mall.fulfillment',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'order',
      name: 'MallOrder',
      component: '/mall/order',
      meta: {
        title: 'menus.mall.order',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    }
  ]
}
