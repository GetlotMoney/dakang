import { AppRouteRecord } from '@/types/router'

/**
 * 水种套餐路由（一期 5+1）
 * 运行时为后端菜单模式，菜单以 api_rbac_menu 为准，本文件为前端模式镜像（目录 710 + 水种 + 套餐 694）。
 */
export const productRoutes: AppRouteRecord = {
  path: '/product',
  name: 'ProductRoot',
  component: '/index/index',
  meta: {
    title: 'menus.product.title',
    icon: 'ri:drop-line',
    roles: ['R_SUPER', 'R_ADMIN']
  },
  children: [
    {
      path: 'water',
      name: 'ProductWater',
      component: '/product/water',
      meta: {
        title: 'menus.product.water',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    },
    {
      path: 'package',
      name: 'ProductPackage',
      component: '/product/package',
      meta: {
        title: 'menus.product.package',
        keepAlive: true,
        roles: ['R_SUPER', 'R_ADMIN']
      }
    }
  ]
}
