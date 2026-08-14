<script setup lang="ts">
import type { MallCategory, MallProductCard } from '@/api/mall'
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { ContractError } from '@/api/common'
import { mallApi } from '@/api/mall'
import { mallTradeApi } from '@/api/mall-trade'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import ProductCardView from '@/components/mall-product-card.vue'
import { formatFen } from '@/utils/format'
import { goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '商城',
  },
})

const loading = ref(true)
const errorMessage = ref('')
const categories = ref<MallCategory[]>([])
const products = ref<MallProductCard[]>([])
// Wot 的 H5 sticky 会自行补 44px 导航高度；小程序端需显式避开状态栏与 44px 固定导航。
const stickyOffsetTop = (() => {
  let top = 0
  // #ifndef H5
  top = (uni.getWindowInfo().statusBarHeight ?? 0) + 44
  // #endif
  return top
})()

const activeCategoryId = ref('')
/** 购物车角标件数；读失败时归零隐藏角标，绝不显示一个猜出来的数字。 */
const cartQuantity = ref(0)

onShow(refresh)

async function refresh() {
  loading.value = true
  errorMessage.value = ''
  try {
    // 首页一次取全量（已上架集），分类切换走前端过滤——避免每次切换打接口
    const home = await mallApi.home()
    categories.value = home.categories
    products.value = home.products
  }
  catch (error) {
    categories.value = []
    products.value = []
    errorMessage.value = error instanceof ContractError ? error.message : '商城加载失败，请稍后重试'
  }
  finally {
    loading.value = false
  }
  refreshCartBadge()
}

/** 角标是附属信息：读不到就不显示，不影响商品列表本身的加载结论。 */
async function refreshCartBadge() {
  try {
    cartQuantity.value = (await mallTradeApi.cartList()).totalQuantity
  }
  catch {
    cartQuantity.value = 0
  }
}

const visibleProducts = computed(() =>
  activeCategoryId.value
    ? products.value.filter(item => item.categoryId === activeCategoryId.value)
    : products.value,
)

function switchCategory(categoryId: string) {
  activeCategoryId.value = activeCategoryId.value === categoryId ? '' : categoryId
}

function openDetail(item: MallProductCard) {
  goTo('M02', { productId: item.productId })
}
</script>

<template>
  <view class="page-shell">
    <image class="mall-watermark" src="/static/brand/page-watermark.jpg" mode="aspectFill" />
    <AppNavbar title="商城" back-to="U01" />

    <!-- 商城焦点区：品牌一行 + 两个紧凑图标入口。此前是两个等宽描边按钮平铺，
         既不像商城顶栏，也把首屏第一眼让给了「我的订单」。 -->
    <view class="page-section mall-hero">
      <image class="mall-hero__art" src="/static/brand/section-network.jpg" mode="aspectFill" />
      <view class="mall-hero__brand">
        <text class="mall-hero__title">
          六维达康商城
        </text>
        <text class="mall-hero__subtitle">
          饮水用品与到家服务
        </text>
      </view>
      <view class="mall-hero__entries">
        <view class="mall-hero__entry" @click="goTo('M05')">
          <wd-icon name="list" size="20px" />
          <text class="mall-hero__entry-text">
            订单
          </text>
        </view>
        <view class="mall-hero__entry" @click="goTo('M10')">
          <wd-icon name="service" size="20px" />
          <text class="mall-hero__entry-text">
            售后
          </text>
        </view>
        <view class="mall-hero__entry" @click="goTo('M03')">
          <view class="mall-hero__cart-icon">
            <wd-icon name="cart" size="20px" />
            <text v-if="cartQuantity" class="mall-hero__cart-count num">
              {{ cartQuantity > 99 ? '99+' : cartQuantity }}
            </text>
          </view>
          <text class="mall-hero__entry-text">
            购物车
          </text>
        </view>
      </view>
    </view>

    <view v-if="loading" class="page-section">
      <AppPageState state="loading" :row-col="[1, [{ width: '48%' }, { width: '48%', marginLeft: '4%' }], [{ width: '48%' }, { width: '48%', marginLeft: '4%' }]]" />
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain size="small" @click="refresh">
            重新加载
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else>
      <!-- offset-top 必须显式给：wd-sticky 默认 0，且只在 H5 分支补 44px 导航高度，
           小程序端补 0——不给的话分类条会吸到状态栏底下、被自定义导航整条盖住。
           算法与 mall/orders.vue 同源。 -->
      <wd-sticky v-if="categories.length" :offset-top="stickyOffsetTop">
        <scroll-view class="category-bar" scroll-x :show-scrollbar="false">
          <text
            v-for="category in categories"
            :key="category.categoryId"
            class="category-chip"
            :class="{ 'category-chip--active': activeCategoryId === category.categoryId }"
            @click="switchCategory(category.categoryId)"
          >
            {{ category.categoryName }}
          </text>
        </scroll-view>
      </wd-sticky>

      <view v-if="!visibleProducts.length" class="page-section">
        <AppPageState state="empty" message="换个分类看看" />
      </view>

      <view v-else class="page-section product-grid">
        <ProductCardView
          v-for="item in visibleProducts"
          :key="item.productId"
          :name="item.productName"
          :subtitle="item.productSubtitle"
          :price="item.minSalePriceFen === undefined ? '' : `${formatFen(item.minSalePriceFen)} 起`"
          :cover-url="item.coverUrl"
          :in-stock="item.inStock"
          @click="openDetail(item)"
        />
      </view>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.mall-hero {
  position: relative;
  display: flex;
  overflow: hidden;
  align-items: center;
  justify-content: space-between;
  min-height: 86px;
  margin: 0 -16px;
  padding: var(--sp-4) 16px;

  &__art {
    position: absolute;
    z-index: 0;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
  }

  &__brand,
  &__entries {
    position: relative;
    z-index: 1;
  }

  &__brand {
    flex: 1;
    min-width: 0;
  }

  &__title {
    display: block;
    font-size: var(--fs-metric);
    font-weight: 600;
  }

  &__subtitle {
    display: block;
    margin-top: var(--sp-1);
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
  }

  &__entries {
    display: flex;
    flex: none;
    gap: var(--sp-3);
    align-items: center;
  }

  // 图标入口而非描边按钮：顶栏第一眼应该是「这是商城」，不是两个操作
  &__entry {
    display: flex;
    flex-direction: column;
    align-items: center;
    min-width: 40px;
    color: var(--app-text-secondary);
  }

  &__entry-text {
    margin-top: 2px;
    font-size: var(--fs-note);
  }

  &__cart-icon {
    position: relative;
    display: flex;
  }

  &__cart-count {
    position: absolute;
    top: -8px;
    left: 14px;
    min-width: 14px;
    padding: 1px 4px;
    border-radius: var(--r-pill);
    color: var(--app-text-inverse);
    font-size: 9px;
    line-height: 1.2;
    text-align: center;
    background: var(--app-color-danger);
  }
}

.entry-row {
  display: flex;
  gap: 24rpx;
  align-items: center;
  justify-content: flex-end;
}

// 横向滚动条：原实现 display:flex + flex-wrap:wrap 会在容器宽度处换行，
// 永远不产生横向溢出，scroll-x 因此没有任何可滚内容；white-space:nowrap 在 flex 容器上
// 只管文本、管不了 flex 行。改回单行 inline-block 写法才真的能滚。
//
// 吸顶条必须是一道不透光的横带：通栏铺满并补上页底色，否则商品图会从标签缝隙里穿过去。
.category-bar {
  margin: var(--gap-hero) -16px var(--gap-group);
  padding: var(--sp-2) 16px;
  overflow-x: hidden;
  white-space: nowrap;
  background: var(--app-bg-page);
}

.category-chip {
  display: inline-block;
  margin-right: var(--sp-2);
  padding: var(--sp-1) var(--sp-3);
  border-radius: var(--r-pill);
  color: var(--app-text-secondary);
  font-size: var(--fs-caption);
  background: var(--app-bg-card);

  // 选中态用整块浅底 + 主色文字：细描边 Tag 在小屏上几乎看不出选中，
  // 商品详情页的 SKU 面板已经是这个结论，首页分类沿用同一处置。
  &--active {
    color: var(--app-color-primary);
    background: var(--tint-primary);
    font-weight: 600;
  }
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--gap-block);
}

.page-shell {
  position: relative;
  overflow: hidden;
}

.mall-watermark {
  position: absolute;
  z-index: 0;
  top: 0;
  left: 0;
  width: 100%;
  height: 100vh;
  pointer-events: none;
}

.mall-hero,
.page-section,
:deep(.wd-sticky) {
  position: relative;
  z-index: 1;
}
</style>
