<script setup lang="ts">
import type { SkeletonRowCol } from 'wot-design-uni/components/wd-skeleton/types'

/**
 * 页面状态位：加载 / 空 / 失败 / 无权限。
 *
 * 四类状态外观必须可区分——把"没有数据"和"你没有权限看"画成同一张图，用户会一直重试
 * 一件永远不会成功的事。因此图标与文案分档，不共用一张占位图。
 *
 * loading 用与真实内容同构的骨架而不是一行「加载中…」：文字加载态会让页面在数据到达时
 * 整屏跳变，骨架能把最终布局先占住。
 *
 * 本组件不吞异常、不判断权限：调用方给什么状态就画什么，错误文案由调用方传入。
 */
withDefaults(defineProps<{
  /** 状态：加载中 / 空数据 / 加载失败 / 无权限。 */
  state: 'loading' | 'empty' | 'error' | 'blocked'
  /** 失败或受限时的说明文字，由调用方给出，不在组件内编造。 */
  message?: string
  /** 状态标题；业务阻断等场景可覆盖默认标题，但不得由组件推导。 */
  title?: string
  /** 空态可按业务域覆盖图标；错误与受限态仍由组件固定区分。 */
  emptyIcon?: string
  /** 骨架行列结构，默认按「标题 + 两行正文」占位。 */
  rowCol?: SkeletonRowCol[]
}>(), {
  message: '',
  emptyIcon: 'file',
  rowCol: () => [1, 1, { width: '60%' }],
})

const ICONS = {
  empty: 'file',
  error: 'warn-bold',
  blocked: 'lock-on',
} as const

const TITLES = {
  empty: '暂无内容',
  error: '加载失败',
  blocked: '暂无查看权限',
} as const
</script>

<template>
  <view class="page-state">
    <wd-skeleton
      v-if="state === 'loading'"
      :row-col="rowCol"
      animation="gradient"
      :loading="true"
    />

    <view v-else class="page-state__body">
      <wd-icon
        :name="state === 'empty' ? emptyIcon : ICONS[state]"
        size="48px"
        :color="state === 'error' ? 'var(--app-color-danger)' : 'var(--app-text-disabled)'"
      />
      <text class="page-state__title">
        {{ title || TITLES[state] }}
      </text>
      <text v-if="message" class="page-state__desc">
        {{ message }}
      </text>
      <view v-if="$slots.actions" class="status-actions">
        <slot name="actions" />
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page-state {
  padding: var(--sp-5) 0;

  &__body {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: var(--sp-6) var(--sp-4);
  }

  &__title {
    margin-top: var(--sp-3);
    font-size: var(--fs-body);
    font-weight: 500;
  }

  &__desc {
    margin-top: var(--sp-2);
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
    text-align: center;
  }

  .status-actions {
    margin-top: var(--sp-4);
  }
}
</style>
