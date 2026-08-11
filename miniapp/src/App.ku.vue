<script setup lang="ts">
import type { ConfigProviderThemeVars } from 'wot-design-uni'
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { evaluateRouteAccess } from '@/router/guard'
import { useAccountStore } from '@/store/account'
import AppTabbar from '@/tabbar/index.vue'
import { isTabbarPage, syncTabbarByCurrentPage } from '@/tabbar/store'

const showTabbar = ref(false)
const accountStore = useAccountStore()
let handlingDirectAccess = false

// UI-MINIAPP-C-V2-ALL-V1：Wot UI 与页面主题使用同一套森氧生活色值。
const themeVars: ConfigProviderThemeVars = {
  colorTheme: '#456F45',
  colorSuccess: '#5F8A57',
  colorWarning: '#D9A441',
  buttonPrimaryBgColor: '#456F45',
  buttonPrimaryColor: '#FFFDF7',
}

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
  showTabbar.value = isTabbarPage()

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
    showTabbar.value = false
    handleDirectAccessDenied(decision.message)
  }
})
</script>

<template>
  <wd-config-provider :theme-vars="themeVars">
    <KuRootView />
    <AppTabbar v-if="showTabbar" />
  </wd-config-provider>
</template>
