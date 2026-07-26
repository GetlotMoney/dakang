import type { TabbarName } from './config'
import { reactive } from 'vue'
import { tabbarItems } from './config'

function normalizePath(path?: string) {
  if (!path) {
    return ''
  }
  const value = path.split('?')[0]
  return value.startsWith('/') ? value : `/${value}`
}

function currentPagePath() {
  const pages = getCurrentPages()
  return normalizePath(pages[pages.length - 1]?.route)
}

function findNameByPath(path: string): TabbarName | undefined {
  return tabbarItems.find(item => item.pagePath === normalizePath(path))?.name
}

export const tabbarStore = reactive({
  current: 'home' as TabbarName,
  setCurrent(value: TabbarName) {
    this.current = value
  },
})

export function isTabbarPage(path = currentPagePath()) {
  return Boolean(findNameByPath(path))
}

export function syncTabbarByCurrentPage() {
  const name = findNameByPath(currentPagePath())
  if (name) {
    tabbarStore.setCurrent(name)
  }
}
