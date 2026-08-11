<script setup lang="ts">
import { onMounted } from 'vue'
import { tabbarItems } from './config'
import { syncTabbarByCurrentPage, tabbarStore } from './store'

onMounted(() => {
  // #ifndef MP-WEIXIN
  uni.hideTabBar()
  // #endif
})

function handleChange({ value }: { value: string | number }) {
  const target = tabbarItems.find(item => item.name === value)
  if (!target) {
    return
  }
  // 防重守卫必须对比真实页面路径：v-model 在 change 触发前已改写 tabbarStore.current，
  // 用 store 值判断会恒等提前返回，导致 switchTab 永不执行（点击 Tab 无法跳转）。
  const rawPath = getCurrentPages().at(-1)?.route ?? ''
  const currentPath = rawPath.startsWith('/') ? rawPath : `/${rawPath}`
  if (currentPath === target.pagePath) {
    return
  }
  uni.switchTab({
    url: target.pagePath,
    // 切换失败时按真实页面回摆高亮，避免高亮与页面脱节。
    fail: () => syncTabbarByCurrentPage(),
  })
}
</script>

<template>
  <wd-tabbar
    v-model="tabbarStore.current"
    fixed
    placeholder
    safe-area-inset-bottom
    shape="round"
    :bordered="false"
    active-color="#456f45"
    inactive-color="#9baa8f"
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
