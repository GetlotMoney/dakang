<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { evaluateRouteAccess } from '@/router/guard'
import { useAccountStore } from '@/store/account'
import { syncTabbarByCurrentPage } from '@/tabbar/store'

const accountStore = useAccountStore()
let handlingDirectAccess = false

// 主题变量全部收在 style/index.scss 的 :root, page 里，本组件不再传 theme-vars。
// 曾经两处各写一份（这里 4 条 + index.scss 1 条），只覆盖了 theme/success，
// warning 与 danger 漏在 Wot 默认值上，页面为对齐 wd-tag 手抄出 22 处硬编码色。
// ConfigProvider 保留是因为它是 KuRootView 的宿主，不再承担配色职责。

function currentPageUrl() {
  const pages = getCurrentPages()
  const page = pages[pages.length - 1] as {
    route?: string
    options?: Record<string, string | number>
  }
  if (!page?.route) {
    return ''
  }
  const query = Object.entries(page.options ?? {})
    .map(([name, value]) => `${encodeURIComponent(name)}=${encodeURIComponent(String(value))}`)
    .join('&')
  return query ? `/${page.route}?${query}` : `/${page.route}`
}

function handleDirectAccessDenied(message: string) {
  if (handlingDirectAccess) {
    return
  }
  handlingDirectAccess = true
  uni.showModal({
    title: '无法进入',
    content: message,
    showCancel: false,
    complete: () => {
      const target = accountStore.context
        ? '/pages/user/home/index'
        : '/pages/entry/index'
      const navigate = accountStore.context ? uni.switchTab : uni.reLaunch
      navigate({
        url: target,
        complete: () => {
          handlingDirectAccess = false
        },
      })
    },
  })
}

onShow(async () => {
  syncTabbarByCurrentPage()

  const url = currentPageUrl()
  if (!url) {
    return
  }
  if (!accountStore.restored) {
    try {
      await accountStore.restoreSession()
    }
    catch {
      // 会话错误由统一入口呈现，不能在根组件伪造降级会话。
    }
  }
  const decision = evaluateRouteAccess(url, accountStore.context)
  if (!decision.allowed) {
    handleDirectAccessDenied(decision.message)
  }
})
</script>

<template>
  <wd-config-provider>
    <KuRootView />
  </wd-config-provider>
</template>
