import { AppRouteRecord } from '@/types/router'

export const logRoutes: AppRouteRecord = {
  path: '/system/log',
  name: 'Log',
  component: '/index/index',
  redirect: '/system/log/login',
  meta: {
    title: 'menus.system.log.title',
    icon: 'ri:file-list-3-line',
    roles: ['R_SUPER', 'R_ADMIN']
  },
  children: [
    {
      path: 'login',
      name: 'LoginLog',
      component: '/system/log/login/index',
      meta: {
        title: 'menus.system.log.login',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'operation',
      name: 'OperationLog',
      component: '/system/log/operation/index',
      meta: {
        title: 'menus.system.log.operation',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    }
  ]
}
