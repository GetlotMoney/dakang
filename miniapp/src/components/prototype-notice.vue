<script setup lang="ts">
import type { ApiDomain } from '@/api/runtime'
import { computed } from 'vue'
import { noticeTextOf } from './runtime-notice'

/**
 * 页面顶部提示条。
 *
 * - 传 text：页面自己给的一句话优先；
 * - 传 domain：取该域登记的文案；
 * - 两者都没有（或该域未登记）：<b>整条不渲染</b>，不显示空提示条，也不兜一句套话。
 */
const props = defineProps<{ text?: string, domain?: ApiDomain }>()

const displayText = computed(() => props.text ?? noticeTextOf(props.domain))
</script>

<template>
  <view v-if="displayText" class="prototype-notice">
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
