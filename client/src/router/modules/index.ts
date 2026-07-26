import { AppRouteRecord } from '@/types/router'
import { dashboardRoutes } from './dashboard'
import { systemRoutes } from './system'
import { logRoutes } from './log'
import { stationRoutes } from './station'
import { deviceRoutes } from './device'
import { productRoutes } from './product'
import { userRoutes } from './user'
import { orderRoutes } from './order'

/**
 * 导出所有模块化路由
 * 一期 5+1：水站 /station、设备中控 /device、水种套餐 /product、用户 /user、订单中心 /order。
 * 运行时为后端菜单模式，本处静态路由与 api_rbac_menu 保持镜像，两处修改必须同步。
 */
export const routeModules: AppRouteRecord[] = [
  dashboardRoutes,
  stationRoutes,
  deviceRoutes,
  productRoutes,
  userRoutes,
  orderRoutes,
  systemRoutes,
  logRoutes
]
