import type { TabBar } from '@uni-helper/vite-plugin-uni-pages'

export type TabbarName = 'home' | 'order' | 'profile'

export interface TabbarItem {
  name: TabbarName
  text: string
  pagePath: string
  icon: string
}

export const tabbarItems: TabbarItem[] = [
  {
    name: 'home',
    text: '首页',
    pagePath: '/pages/user/home/index',
    icon: 'home',
  },
  {
    name: 'order',
    text: '订单',
    pagePath: '/pages/user/order/index',
    icon: 'cart',
  },
  {
    name: 'profile',
    text: '我的',
    pagePath: '/pages/user/profile/index',
    icon: 'user',
  },
]

export const tabBar: TabBar = {
  custom: true,
  color: '#8A8F99',
  selectedColor: '#5D87FF',
  backgroundColor: '#FFFFFF',
  borderStyle: 'black',
  list: tabbarItems.map(item => ({
    pagePath: item.pagePath.slice(1),
    text: item.text,
  })) as TabBar['list'],
}
