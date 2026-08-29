<script setup lang="ts">
import { onMounted } from 'vue'
import { tabbarItems } from './config'
import { syncTabbarByCurrentPage, tabbarStore } from './store'

onMounted(() => {
  // #ifndef MP-WEIXIN
  uni.hideTabBar()
  // #endif
})

function handleTap(name: string) {
  const target = tabbarItems.find(item => item.name === name)
  if (!target) {
    return
  }
  // 防重守卫必须对比真实页面路径：高亮状态在点击瞬间先行改写，
  // 用 store 值判断会恒等提前返回，导致 switchTab 永不执行（点击 Tab 无法跳转）。
  const rawPath = getCurrentPages().at(-1)?.route ?? ''
  const currentPath = rawPath.startsWith('/') ? rawPath : `/${rawPath}`
  if (currentPath === target.pagePath) {
    return
  }
  tabbarStore.current = target.name
  uni.switchTab({
    url: target.pagePath,
    // 微信切换 Tab 时会触发根组件 onShow；其早期回调仍可能读到旧页面并把高亮回写成旧值。
    // 成功回调发生在新页面落定后，再以本次目标收口，保证页面与高亮最终一致。
    success: () => tabbarStore.setCurrent(target.name),
    // 切换失败时按真实页面回摆高亮并提示，避免看起来像点击无效。
    fail: () => {
      syncTabbarByCurrentPage()
      uni.showToast({ title: '页面切换失败，请重试', icon: 'none' })
    },
  })
}

function handleChange({ value }: { value: string | number }) {
  handleTap(String(value))
}
</script>

<template>
  <!-- 使用 Wot 官方 fixed/placeholder/safeAreaInsetBottom：AppTabbar 挂在 KuRootView 外层，
       自绘 position:fixed 在微信组件边界中会出现文字可访问但实际边界为 0 的情况。 -->
  <wd-tabbar
    v-model="tabbarStore.current"
    fixed
    placeholder
    safe-area-inset-bottom
    :z-index="500"
    @change="handleChange"
  >
    <wd-tabbar-item
      v-for="item in tabbarItems"
      :key="item.name"
      :name="item.name"
      :title="item.text"
      :icon="item.icon"
    />
  </wd-tabbar>
</template>
