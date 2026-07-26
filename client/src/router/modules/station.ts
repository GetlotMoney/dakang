import { AppRouteRecord } from '@/types/router'

/**
 * 水站管理路由（一期 5+1）
 * 运行时为后端菜单模式（VITE_ACCESS_MODE=backend），菜单以 api_rbac_menu 为准；
 * 本文件用于前端路由模式，并与数据库菜单保持一致；两处变更必须同步。
 */
export const stationRoutes: AppRouteRecord = {
  path: '/station',
  name: 'StationRoot',
  component: '/index/index',
  meta: {
    title: 'menus.station.title',
    icon: 'ri:map-pin-2-line',
    roles: ['R_SUPER', 'R_ADMIN']
  },
  children: [
    {
      path: 'index',
      name: 'StationList',
      component: '/station/index',
      meta: {
        title: 'menus.station.list',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    }
  ]
}
