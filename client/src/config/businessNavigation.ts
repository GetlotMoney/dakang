/**
 * PC 后台业务导航契约。
 *
 * 本配置定义用户可见的业务入口，不表示 Vue Router 或 RBAC 的父子结构。
 * 完整路由、权限菜单仍可保持嵌套；侧栏按岗位职责投影为一级工作区。
 */

export type BusinessModuleKey =
  | 'device'
  | 'product'
  | 'user'
  | 'order'
  | 'finance'
  | 'deliveryOps'
  | 'mall'

export interface BusinessModuleNavItem {
  label: string
  path: string
  icon: string
}

export interface BusinessModuleNavigation {
  title: string
  items: readonly BusinessModuleNavItem[]
}

export interface PrimaryBusinessMenuContract {
  key: 'dashboard' | 'station' | BusinessModuleKey
  rootPath: string
  defaultPath: string
  /** 同一授权根可以按职责拆成多个可见工作区；这里只列该工作区拥有的真实页面。 */
  ownedPaths?: readonly string[]
  /** 展示投影可改标题和图标，但不改真实路由、菜单 ID 或权限。 */
  displayTitle?: string
  displayIcon?: string
  /** 仪表盘父级名称是“仪表盘”，展示时应采用子页“运营总览”的名称。 */
  useLandingTitle?: boolean
}

/**
 * 持久化登录态所对应的后台导航契约版本。
 *
 * 后端菜单与前端组件是一个整体契约；删除/重命名页面后必须升级此值，
 * 使各 origin（尤其 13321 与 8081）中的旧菜单不能继续注册已经不存在的组件。
 */
export const DEMO_SESSION_SCHEMA_VERSION = 'pc-navigation-20260814-v10'

/** 用户可见工作区的固定落点，不依赖菜单排序推导。 */
export const PRIMARY_BUSINESS_MENU_CONTRACTS: readonly PrimaryBusinessMenuContract[] = [
  {
    key: 'dashboard',
    rootPath: '/dashboard',
    defaultPath: '/dashboard/console',
    useLandingTitle: true
  },
  { key: 'station', rootPath: '/station', defaultPath: '/station/index' },
  { key: 'device', rootPath: '/device', defaultPath: '/device/index' },
  { key: 'product', rootPath: '/product', defaultPath: '/product/water' },
  { key: 'user', rootPath: '/user', defaultPath: '/user/index' },
  {
    key: 'order',
    rootPath: '/order',
    defaultPath: '/order/index',
    ownedPaths: ['/order/index', '/order/delivery', '/order/appeal'],
    displayTitle: '订单中心',
    displayIcon: 'ri:file-list-3-line'
  },
  {
    key: 'finance',
    rootPath: '/order',
    defaultPath: '/order/split',
    ownedPaths: ['/order/split', '/order/reconcile', '/order/splitconfig', '/order/attribution'],
    displayTitle: '财务结算',
    displayIcon: 'ri:funds-line'
  },
  {
    key: 'deliveryOps',
    rootPath: '/order',
    defaultPath: '/order/autorule',
    ownedPaths: ['/order/autorule', '/order/waterstats'],
    displayTitle: '配送运营',
    displayIcon: 'ri:truck-line'
  },
  { key: 'mall', rootPath: '/mall', defaultPath: '/mall/product' }
]

/** 多页面业务域在页面内部显示的导航。 */
export const BUSINESS_MODULE_NAVIGATION: Record<BusinessModuleKey, BusinessModuleNavigation> = {
  device: {
    title: '设备中控',
    items: [
      { label: '设备档案', path: '/device/index', icon: 'ri:cpu-line' },
      { label: '告警中心', path: '/device/alarm', icon: 'ri:alarm-warning-line' },
      { label: '运维工单', path: '/device/workorder', icon: 'ri:file-list-3-line' },
      { label: '批量控制', path: '/device/batch', icon: 'ri:stack-line' },
      { label: '指令记录', path: '/device/command', icon: 'ri:terminal-box-line' },
      { label: '运营配置', path: '/device/operations', icon: 'ri:settings-3-line' }
    ]
  },
  product: {
    title: '水种套餐',
    items: [
      { label: '水种管理', path: '/product/water', icon: 'ri:drop-line' },
      { label: '套餐管理', path: '/product/package', icon: 'ri:coupon-3-line' }
    ]
  },
  user: {
    title: '用户管理',
    items: [
      { label: '用户列表', path: '/user/index', icon: 'ri:user-3-line' },
      { label: '水卡与授权', path: '/user/card', icon: 'ri:bank-card-line' },
      { label: '配送员准入', path: '/user/courier', icon: 'ri:user-follow-line' },
      { label: '消息记录', path: '/user/message', icon: 'ri:notification-3-line' }
    ]
  },
  order: {
    title: '订单中心',
    items: [
      { label: '订单查询', path: '/order/index', icon: 'ri:file-search-line' },
      { label: '配送任务', path: '/order/delivery', icon: 'ri:truck-line' },
      { label: '申诉处理', path: '/order/appeal', icon: 'ri:customer-service-2-line' }
    ]
  },
  finance: {
    title: '财务结算',
    items: [
      { label: '分账明细', path: '/order/split', icon: 'ri:pie-chart-2-line' },
      { label: '日对账', path: '/order/reconcile', icon: 'ri:scales-3-line' },
      { label: '分账比例配置', path: '/order/splitconfig', icon: 'ri:percent-line' },
      { label: '分润归属', path: '/order/attribution', icon: 'ri:git-branch-line' }
    ]
  },
  deliveryOps: {
    title: '配送运营',
    items: [
      { label: '自动补货规则', path: '/order/autorule', icon: 'ri:refresh-line' },
      { label: '水种用量统计', path: '/order/waterstats', icon: 'ri:bar-chart-2-line' }
    ]
  },
  mall: {
    title: '商城管理',
    items: [
      { label: '商品管理', path: '/mall/product', icon: 'ri:shopping-bag-3-line' },
      { label: '分类管理', path: '/mall/category', icon: 'ri:price-tag-3-line' },
      { label: '前置仓管理', path: '/mall/warehouse', icon: 'ri:building-2-line' },
      { label: '库存管理', path: '/mall/stock', icon: 'ri:archive-2-line' },
      { label: '商城订单', path: '/mall/order', icon: 'ri:file-list-3-line' },
      { label: '商城履约', path: '/mall/fulfillment', icon: 'ri:truck-line' },
      { label: '商城售后', path: '/mall/aftersale', icon: 'ri:refund-2-line' }
    ]
  }
}
