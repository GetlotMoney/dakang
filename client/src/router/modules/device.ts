import { AppRouteRecord } from '@/types/router'

/**
 * 设备中控路由（一期 5+1）
 * 运行时为后端菜单模式（VITE_ACCESS_MODE=backend），菜单以 api_rbac_menu 为准；
 * 本文件用于前端路由模式，并与数据库菜单保持一致；两处变更必须同步。
 */
export const deviceRoutes: AppRouteRecord = {
  path: '/device',
  name: 'DeviceRoot',
  component: '/index/index',
  meta: {
    title: 'menus.device.title',
    icon: 'ri:cpu-line',
    roles: ['R_SUPER', 'R_ADMIN']
  },
  children: [
    {
      path: 'index',
      name: 'DeviceList',
      component: '/device/index',
      meta: {
        title: 'menus.device.list',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'command',
      name: 'DeviceCommand',
      component: '/device/command',
      meta: {
        title: 'menus.device.command',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'operations',
      name: 'DeviceOperations',
      component: '/device/operations',
      meta: {
        title: 'menus.device.operations',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'detail',
      name: 'DeviceDetail',
      component: '/device/detail',
      meta: {
        title: 'menus.device.detail',
        isHide: true,
        activePath: '/device/index',
        roles: ['R_SUPER', 'R_ADMIN']
      }
    }
  ]
}
