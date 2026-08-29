import type { TabBar } from '@uni-helper/vite-plugin-uni-pages'

export type TabbarName = 'home' | 'order' | 'profile'

export interface TabbarItem {
  name: TabbarName
  text: string
  pagePath: string
  icon: string
  iconPath: string
  selectedIconPath: string
}

export const tabbarItems: TabbarItem[] = [
  {
    name: 'home',
    text: '首页',
    pagePath: '/pages/user/home/index',
    icon: 'home',
    iconPath: 'static/tabbar/home.png',
    selectedIconPath: 'static/tabbar/home-active.png',
  },
  {
    name: 'order',
    text: '订单',
    pagePath: '/pages/user/order/index',
    icon: 'list',
    iconPath: 'static/tabbar/order.png',
    selectedIconPath: 'static/tabbar/order-active.png',
  },
  {
    name: 'profile',
    text: '我的',
    pagePath: '/pages/user/profile/index',
    icon: 'user-circle',
    iconPath: 'static/tabbar/profile.png',
    selectedIconPath: 'static/tabbar/profile-active.png',
  },
]

/**
 * 微信小程序使用原生 Tabbar，优先保证三个主入口稳定存在；图标与选中图标均为本地 PNG，
 * 不再退化成只有三个小字。H5 等非微信端仍可复用 tabbar/index.vue 的 Wot Tabbar。
 * `list` 不可删：switchTab 依赖它做路由登记。
 */
export const tabBar: TabBar = {
  custom: false,
  color: '#646A73',
  selectedColor: '#2E7CF6',
  backgroundColor: '#FFFFFF',
  borderStyle: 'white',
  list: tabbarItems.map(item => ({
    pagePath: item.pagePath.slice(1),
    text: item.text,
    iconPath: item.iconPath,
    selectedIconPath: item.selectedIconPath,
  })) as TabBar['list'],
}
