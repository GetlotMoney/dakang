<script setup lang="ts">
import type { ApiDomain } from '@/api/runtime'
import { computed } from 'vue'
import { currentMode } from '@/api/runtime'
import { prototypeNoticeText, resolvePrototypeNoticeKey } from './runtime-notice'

/**
 * 全页面统一的运行边界标识（工作流 D3 + E2E-03 验收 P1-4）。
 *
 * - 未传参：保持历史原型口径（mock 页面零改动）；
 * - 传 domain：按该域构建期模式在组件内分流 mock/real 文案（判定收敛在
 *   runtime-notice 纯函数，页面只声明所属域，不各写一份判断）；
 * - 传 text：页面级精确口径优先（既有接真页面的自定义文案不受影响）。
 */
const props = defineProps<{ text?: string, domain?: ApiDomain }>()

const displayText = computed(() =>
  props.text
  ?? prototypeNoticeText(resolvePrototypeNoticeKey(props.domain, props.domain ? currentMode(props.domain) : 'mock')),
)
</script>

<template>
  <view class="prototype-notice">
    <wd-notice-bar :text="displayText" prefix="warn-bold" type="info" wrapable :scrollable="false" />
  </view>
</template>

<style scoped lang="scss">
.prototype-notice {
  margin-top: 8px;
  border-radius: 8px;
  overflow: hidden;
}
</style>
