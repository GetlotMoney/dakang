<script setup lang="ts">
/**
 * 吸底动作栏：只负责安全区、定位、阴影与等高占位。
 *
 * 不推导任何 canPay / canSubmit / canSign——按钮的可用性由调用页的业务状态决定，
 * 组件替页面判断可用性，就等于把业务守卫复制了一份，两份迟早不一致。
 *
 * placeholder 与栏体等高：没有占位时列表最后一行会被永久遮住，用户以为加载不全。
 */
defineProps<{
  /** 左侧摘要区（合计、已选数等）是否存在由插槽决定。 */
  bordered?: boolean
}>()
</script>

<template>
  <view class="action-bar-placeholder" />
  <view class="action-bar" :class="{ 'action-bar--bordered': bordered }">
    <view v-if="$slots.summary" class="action-bar__summary">
      <slot name="summary" />
    </view>
    <view class="action-bar__actions" :class="{ 'action-bar__actions--full': !$slots.summary }">
      <slot name="secondary" />
      <slot name="primary" />
    </view>
  </view>
</template>

<style lang="scss" scoped>
// 与 .action-bar 等高，按最高的一档算：size="large" 按钮 44px + 上下内边距 12×2 = 68。
// 按 64 算会让列表最后一行被压掉 4px——正好是「看起来没加载全」的那种缺陷。
.action-bar-placeholder {
  height: calc(68px + constant(safe-area-inset-bottom));
  height: calc(68px + env(safe-area-inset-bottom));
}

.action-bar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-sizing: border-box;
  min-height: 68px;
  padding: var(--sp-3) var(--sp-4);
  padding-bottom: calc(var(--sp-3) + constant(safe-area-inset-bottom));
  padding-bottom: calc(var(--sp-3) + env(safe-area-inset-bottom));
  background: var(--app-bg-card);
  box-shadow: var(--sh-bar);

  &--bordered {
    border-top: 1px solid var(--line-1);
  }

  &__summary {
    display: flex;
    flex: 1;
    flex-direction: column;
    gap: var(--sp-1);
    min-width: 0;
  }

  // 无 summary 槽时动作区独占整条：否则容器 flex:none 会按内容收窄，
  // 页面给主按钮加 block 也撑不开——付款类页面的通栏主按钮就是这么失效的。
  &__actions {
    display: flex;
    flex: none;
    gap: var(--sp-2);
    align-items: center;

    &--full {
      flex: 1;
      justify-content: flex-end;
    }
  }
}

// 窄屏时不让两个 Wot 中型按钮（各最小 120px）把金额摘要挤成一列。
// 摘要与动作改为上下两行，placeholder 同步增高，正文仍不会被固定栏遮住。
@media (max-width: 360px) {
  .action-bar-placeholder {
    height: calc(88px + constant(safe-area-inset-bottom));
    height: calc(88px + env(safe-area-inset-bottom));
  }

  .action-bar {
    flex-wrap: wrap;
    row-gap: var(--sp-2);

    &__summary {
      flex: 1 0 100%;
      flex-direction: row;
      align-items: baseline;
      justify-content: space-between;
    }

    &__actions {
      justify-content: flex-end;
      width: 100%;
    }
  }
}
</style>
