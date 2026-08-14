<script setup lang="ts">
import { ref, watch } from 'vue'

/**
 * 商品行：购物车、确认订单、订单列表摘要与订单详情共用的一行。
 *
 * 固定层级：缩略图 → 商品名 → 规格 → 数量 → 单价/小计。四个页面各写一份时，
 * 同一件商品在四处的换行、截断与对齐都不一样，用户会怀疑看的不是同一单。
 *
 * 失效行只降饱和并保留原因，不隐藏：藏起来用户就不知道为什么少了一行。
 */
const props = defineProps<{
  name: string
  /** 规格文案，由调用方用既有 formatSpecs 生成。 */
  spec?: string
  /** 已格式化的价格文本，组件不做分转元。 */
  price?: string
  quantity?: number
  coverUrl?: string
  /** 失效原因；有值即整行降权。 */
  unavailableReason?: string
}>()

const imageFailed = ref(false)

watch(() => props.coverUrl, () => {
  imageFailed.value = false
})
</script>

<template>
  <view class="goods-line" :class="{ 'app-is-disabled': !!unavailableReason }">
    <view class="goods-line__cover">
      <image
        v-if="coverUrl && !imageFailed"
        class="goods-line__img"
        :src="coverUrl"
        mode="aspectFill"
        @error="imageFailed = true"
      />
      <view v-else class="goods-line__img goods-line__img--empty">
        <wd-icon name="picture" size="20px" color="var(--app-text-disabled)" />
      </view>
    </view>

    <view class="goods-line__body">
      <text class="goods-line__name">
        {{ name }}
      </text>
      <text v-if="spec" class="goods-line__spec">
        {{ spec }}
      </text>
      <text v-if="unavailableReason" class="goods-line__reason">
        {{ unavailableReason }}
      </text>
    </view>

    <view class="goods-line__right">
      <text v-if="price" class="money">
        {{ price }}
      </text>
      <text v-if="quantity !== undefined" class="goods-line__qty num">
        ×{{ quantity }}
      </text>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.goods-line {
  display: flex;
  gap: var(--sp-3);
  padding: var(--sp-3) 0;

  &__cover {
    flex: none;
  }

  &__img {
    width: 64px;
    height: 64px;
    border-radius: var(--r-sm);
    background: var(--app-bg-page);

    &--empty {
      display: flex;
      align-items: center;
      justify-content: center;
    }
  }

  &__body {
    display: flex;
    flex: 1;
    flex-direction: column;
    gap: var(--sp-1);
    min-width: 0;
  }

  &__name {
    font-size: var(--fs-body);
    line-height: 1.4;
  }

  &__spec {
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
  }

  &__reason {
    color: var(--app-color-warning-text);
    font-size: var(--fs-note);
  }

  &__right {
    display: flex;
    flex: none;
    flex-direction: column;
    align-items: flex-end;
    gap: var(--sp-1);
    font-size: var(--fs-body);
  }

  &__qty {
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
  }
}
</style>
