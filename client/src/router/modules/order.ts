import { AppRouteRecord } from '@/types/router'

/**
 * 订单履约、财务结算与配送运营共享的稳定技术路由根。
 * 运行时为后端菜单模式（VITE_ACCESS_MODE=backend），菜单以 api_rbac_menu 为准；
 * 用户侧按职责投影成三个工作区，真实路径与数据库权限树保持不变。
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
    },
    {
      path: 'split',
      name: 'OrderSplit',
      component: '/order/split',
      meta: {
        title: 'menus.order.split',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'reconcile',
      name: 'OrderReconcile',
      component: '/order/reconcile',
      meta: {
        title: 'menus.order.reconcile',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'splitconfig',
      name: 'OrderSplitConfig',
      component: '/order/splitconfig',
      meta: {
        title: 'menus.order.splitconfig',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'attribution',
      name: 'OrderAttribution',
      component: '/order/attribution',
      meta: {
        title: 'menus.order.attribution',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'autorule',
      name: 'OrderAutoRule',
      component: '/order/autorule',
      meta: {
        title: 'menus.order.autorule',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'waterstats',
      name: 'OrderWaterStats',
      component: '/order/waterstats',
      meta: {
        title: 'menus.order.waterstats',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    }
  ]
}
