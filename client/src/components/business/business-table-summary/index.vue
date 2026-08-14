<template>
  <div class="business-table-summary" aria-live="polite">
    <span class="summary-primary"
      >筛选结果 <strong>{{ total }}</strong
      >{{ unit }}</span
    >
    <span class="summary-divider" aria-hidden="true"></span>
    <span>本页 {{ pageSize }}{{ unit }}</span>
    <slot></slot>
  </div>
</template>

<script setup lang="ts">
  interface Props {
    total: number
    pageSize: number
    unit?: string
  }

  withDefaults(defineProps<Props>(), {
    unit: '条'
  })
</script>

<style scoped>
  .business-table-summary {
    display: inline-flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    min-height: 32px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }

  .summary-primary {
    color: var(--el-text-color-regular);
  }

  .summary-primary strong {
    margin: 0 3px;
    color: var(--el-text-color-primary);
    font-size: 15px;
  }

  .summary-divider {
    width: 1px;
    height: 14px;
    background: var(--el-border-color);
  }
</style>
