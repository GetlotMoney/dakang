<script setup lang="ts">
import type { RouteId } from '@/router/routes'
import { backOr } from '@/utils/navigation'

/** 非 Tab 页统一顶部导航：适配胶囊安全区，返回优先页面栈、直达时落回所属模块。 */
const props = defineProps<{
  title: string
  backTo: RouteId
}>()

function handleBack() {
  backOr(props.backTo)
}
</script>

<template>
  <wd-navbar
    :title="title"
    left-arrow
    fixed
    placeholder
    safe-area-inset-top
    :bordered="false"
    @click-left="handleBack"
  />
</template>
