import { AppRouteRecord } from '@/types/router'

/**
 * 订单中心路由（一期 5+1，Demo 阶段使用 Mock 契约）
 * 运行时为后端菜单模式（VITE_ACCESS_MODE=backend），菜单以 api_rbac_menu 为准；
 * 本文件用于前端路由模式，并与数据库菜单保持一致；两处变更必须同步。
 */
export const orderRoutes: AppRouteRecord = {
  path: '/order',
  name: 'OrderRoot',
  component: '/index/index',
  meta: {
    title: 'menus.order.title',
    icon: 'ri:file-list-3-line',
    roles: ['R_SUPER', 'R_ADMIN']
  },
  children: [
    {
      path: 'index',
      name: 'OrderList',
      component: '/order/index',
      meta: {
        title: 'menus.order.list',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'delivery',
      name: 'OrderDelivery',
      component: '/order/delivery',
      meta: {
        title: 'menus.order.delivery',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'appeal',
      name: 'OrderAppeal',
      component: '/order/appeal',
      meta: {
        title: 'menus.order.appeal',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    }
  ]
}
