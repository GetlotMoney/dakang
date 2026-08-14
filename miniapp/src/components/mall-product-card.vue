<script setup lang="ts">
/**
 * 商品卡：商城首页与其他真实商品展示位共用。
 *
 * 固定层级：商品图 → 商品名 → 规格/副标题 → 价格 → 库存状态。
 * 缺货整卡降权但信息仍可读——把缺货商品变成灰块会让用户以为页面坏了。
 *
 * 图片失败时落本地占位，不引外链图。
 */
import { ref, watch } from 'vue'

const props = defineProps<{
  name: string
  /** 副标题或规格，一行。 */
  subtitle?: string
  /** 已格式化的价格文本。 */
  price: string
  coverUrl?: string
  inStock: boolean
}>()

const imageFailed = ref(false)

watch(() => props.coverUrl, () => {
  imageFailed.value = false
})
</script>

<template>
  <!-- 不挂 surface-card：它自带 --sh-card 双层阴影，一屏 6~10 张卡就是十块带影白板在灰底上
       排队，没有一块是主卡，而 M01 要的是「真实商品图为主」。去掉阴影后商品图自己成为卡的主体，
       卡与卡之间靠网格留白分隔就够。 -->
  <view class="product-card pressable" :class="{ 'app-is-disabled': !inStock }">
    <view class="product-card__cover">
      <image
        v-if="coverUrl && !imageFailed"
        class="product-card__img"
        :src="coverUrl"
        mode="aspectFill"
        @error="imageFailed = true"
      />
      <view v-else class="product-card__img product-card__img--empty">
        <image class="product-card__empty-art" src="/static/brand/page-watermark.jpg" mode="aspectFill" />
        <text class="product-card__empty-brand">
          六维达康
        </text>
        <text class="product-card__empty-note">
          商品图片待补充
        </text>
      </view>
    </view>

    <text class="product-card__name">
      {{ name }}
    </text>
    <text v-if="subtitle" class="product-card__sub">
      {{ subtitle }}
    </text>

    <view class="product-card__foot">
      <text class="money product-card__price">
        {{ price }}
      </text>
      <text v-if="!inStock" class="product-card__stock">
        缺货
      </text>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.product-card {
  padding: var(--sp-3);
  border-radius: var(--r-md);
  background: var(--app-bg-card);
  display: flex;
  flex-direction: column;
  padding: var(--sp-2) var(--sp-2) var(--sp-3);

  &__cover {
    width: 100%;
  }

  // 1:1 商品图：整列商品高度一致，扫读时视线不跳
  &__img {
    width: 100%;
    aspect-ratio: 1;
    border-radius: var(--r-sm);
    background: var(--app-bg-page);

    &--empty {
      position: relative;
      display: flex;
      overflow: hidden;
      flex-direction: column;
      align-items: center;
      justify-content: center;
    }
  }

  &__empty-art {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
  }

  &__empty-brand,
  &__empty-note {
    position: relative;
    z-index: 1;
  }

  &__empty-brand {
    color: var(--app-color-primary-deep);
    font-size: var(--fs-caption);
    font-weight: 700;
    letter-spacing: 2px;
  }

  &__empty-note {
    margin-top: var(--sp-1);
    color: var(--app-text-tertiary);
    font-size: var(--fs-note);
  }

  // 名称固定占两行高：网格是 align-items:stretch 的等高卡，卡内却从顶部往下堆，
  // 一行名与两行名会把价格推到不同高度——同一行左右两张卡的价格差一整行。
  &__name {
    display: -webkit-box;
    overflow: hidden;
    min-height: calc(1.35em * 2);
    margin-top: var(--sp-2);
    font-size: var(--fs-body);
    line-height: 1.35;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }

  &__sub {
    overflow: hidden;
    margin-top: var(--sp-1);
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  // 价格钉在卡底：让它挂在卡片下边界这条轴上，而不是「内容有多长」
  &__foot {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    margin-top: auto;
    padding-top: var(--sp-2);
  }

  &__price {
    font-size: var(--fs-title);
    font-weight: 600;
  }

  &__stock {
    color: var(--app-text-tertiary);
    font-size: var(--fs-note);
  }
}
</style>
