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

/**
 * 原生 tabBar 声明。`custom: true` 下微信不渲染它，可见的是 tabbar/index.vue 的 wd-tabbar，
 * 因此下面的配色**改了也不会影响线上观感**——真正生效的是 style/index.scss 里的
 * `--wot-tabbar-*` 变量。此处仍与品牌 token 对齐，是为了让 `list` 之外的字段不再自述一套
 * 早已作废的旧色（selectedColor 长期写着 #5D87FF，与主色 #2E7CF6 不是一个值），
 * 避免后来者据此推断线上颜色，或在关掉 custom 时突然掉色。
 * `list` 不可删：switchTab 依赖它做路由登记。
 */
export const tabBar: TabBar = {
  custom: true,
  color: '#646A73',
  selectedColor: '#2E7CF6',
  backgroundColor: '#FFFFFF',
  borderStyle: 'white',
  list: tabbarItems.map(item => ({
    pagePath: item.pagePath.slice(1),
    text: item.text,
  })) as TabBar['list'],
}
