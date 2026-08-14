<script setup lang="ts">
import type { MallProductDetail, MallSkuOption } from '@/api/mall'
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref, watch } from 'vue'
import { useToast } from 'wot-design-uni'
import { ContractError } from '@/api/common'
import { mallApi } from '@/api/mall'
import { encodeCheckoutLines, MALL_LINE_LIMITS, mallTradeApi } from '@/api/mall-trade'
import AppBottomActionBar from '@/components/app-bottom-action-bar.vue'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import { formatFen } from '@/utils/format'
import { backOr, goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '商品详情',
  },
})

const toast = useToast()

const loading = ref(true)
const errorMessage = ref('')
const detail = ref<MallProductDetail | null>(null)
const coverFailed = ref(false)
const activeSkuId = ref('')
const quantity = ref(1)
const adding = ref(false)
let productId = ''

onLoad((query) => {
  productId = typeof query?.productId === 'string' ? query.productId : ''
  refresh()
})

async function refresh() {
  if (!productId) {
    loading.value = false
    errorMessage.value = '缺少商品参数'
    return
  }
  loading.value = true
  errorMessage.value = ''
  coverFailed.value = false
  try {
    detail.value = await mallApi.productDetail(productId)
    // 默认选中第一个有货 SKU，全部缺货则选第一个
    const first = detail.value.skus.find(sku => sku.inStock) ?? detail.value.skus[0]
    activeSkuId.value = first?.skuId ?? ''
  }
  catch (error) {
    detail.value = null
    errorMessage.value = error instanceof ContractError ? error.message : '商品加载失败，请稍后重试'
  }
  finally {
    loading.value = false
  }
}

const activeSku = computed<MallSkuOption | null>(
  () => detail.value?.skus.find(sku => sku.skuId === activeSkuId.value) ?? null,
)

/** 换规格即回到 1 件：上一个规格挑的件数对新规格没有意义，留着只会误加购。 */
watch(activeSkuId, () => {
  quantity.value = 1
})

/** 缺货与无规格都不允许下单；能否真正成交仍由服务端在结算与创单时再判。 */
const canOrder = computed(() => Boolean(activeSku.value?.inStock))

function specText(sku: MallSkuOption) {
  return Object.values(sku.specs).join(' / ') || sku.skuName
}

async function handleAddToCart() {
  const sku = activeSku.value
  if (adding.value || !sku || !canOrder.value) {
    return
  }
  adding.value = true
  try {
    // increment=true：详情页加购是「再买几件」，覆盖语义只属于购物车页的改量
    await mallTradeApi.cartSave({ skuId: sku.skuId, quantity: quantity.value, increment: true })
    toast.success('已加入购物车')
  }
  catch (error) {
    toast.show(error instanceof ContractError ? error.message : '加入购物车失败，请重试')
  }
  finally {
    adding.value = false
  }
}

/** 立即购买直达结算页，不经购物车：这一单买什么完全由本页选择决定。 */
function handleBuyNow() {
  const sku = activeSku.value
  if (!sku || !canOrder.value) {
    return
  }
  try {
    goTo('M04', {
      lines: encodeCheckoutLines([{ skuId: sku.skuId, quantity: quantity.value }]),
    })
  }
  catch (error) {
    toast.show(error instanceof ContractError ? error.message : '暂时无法结算，请重试')
  }
}
</script>

<template>
  <view class="page-shell" :class="{ 'page-shell--with-bar': !!detail }">
    <AppNavbar title="商品详情" back-to="M01" />
    <wd-toast />

    <view v-if="loading" class="page-section">
      <AppPageState state="loading" :row-col="[1, { width: '100%', height: '200px' }, 1, { width: '60%' }]" />
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain size="small" @click="refresh">
            重新加载
          </wd-button>
          <wd-button plain size="small" @click="backOr('M01')">
            返回商城
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else-if="detail">
      <view class="page-section">
        <image
          v-if="detail.coverUrl && !coverFailed"
          class="detail-cover"
          :src="detail.coverUrl"
          mode="aspectFill"
          @error="coverFailed = true"
        />
        <view v-else class="detail-cover detail-cover-placeholder">
          <text>暂无图片</text>
        </view>
      </view>

      <view class="page-section">
        <view class="detail-title-row">
          <text class="detail-name">
            {{ detail.productName }}
          </text>
          <wd-tag v-if="!detail.inStock" type="default" plain>
            缺货
          </wd-tag>
        </view>
        <text v-if="detail.productSubtitle" class="muted-text detail-subtitle">
          {{ detail.productSubtitle }}
        </text>
      </view>

      <view class="page-section">
        <view class="section-title">
          选择规格
        </view>
        <view v-if="!detail.skus.length" class="muted-text">
          暂无可选规格
        </view>
        <!-- 整块选择面板：细描边的 Tag 在小屏上几乎看不出选中，改成整块浅底+主色边框 -->
        <view v-else class="sku-panel">
          <view
            v-for="sku in detail.skus"
            :key="sku.skuId"
            class="sku-option"
            :class="{
              'sku-option--active': activeSkuId === sku.skuId,
              'sku-option--void': !sku.inStock,
            }"
            @click="activeSkuId = sku.skuId"
          >
            <text class="sku-option__spec">
              {{ specText(sku) }}
            </text>
            <text class="sku-option__price money num">
              {{ formatFen(sku.salePriceFen) }}
            </text>
            <text v-if="!sku.inStock" class="sku-option__void">
              缺货
            </text>
          </view>
        </view>
        <view v-if="activeSku" class="sku-detail">
          <view class="price-row">
            <text class="money sale-price">
              {{ formatFen(activeSku.salePriceFen) }}
            </text>
            <text
              v-if="activeSku.marketPriceFen && activeSku.marketPriceFen > activeSku.salePriceFen"
              class="market-price muted-text"
            >
              {{ formatFen(activeSku.marketPriceFen) }}
            </text>
            <wd-tag :type="activeSku.inStock ? 'success' : 'default'" plain>
              {{ activeSku.inStock ? '有货' : '缺货' }}
            </wd-tag>
          </view>
          <text v-if="activeSku.weightGram > 0" class="muted-text weight-line">
            重量：{{ activeSku.weightGram }} 克
          </text>
          <view class="quantity-row">
            <text>购买数量</text>
            <wd-input-number
              v-model="quantity"
              :min="1"
              :max="MALL_LINE_LIMITS.maxQuantity"
              :disabled="!canOrder"
            />
          </view>
        </view>
      </view>

      <view v-if="detail.productDesc" class="page-section">
        <view class="section-title">
          商品说明
        </view>
        <text class="muted-text desc-text">
          {{ detail.productDesc }}
        </text>
      </view>

      <AppBottomActionBar>
        <template #secondary>
          <wd-button type="icon" icon="cart" aria-label="购物车" @click="goTo('M03')" />
          <wd-button size="small" plain :disabled="!canOrder" :loading="adding" @click="handleAddToCart">
            加入购物车
          </wd-button>
        </template>
        <template #primary>
          <wd-button type="primary" :disabled="!canOrder" @click="handleBuyNow">
            立即购买
          </wd-button>
        </template>
      </AppBottomActionBar>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.sku-panel {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}

.sku-option {
  display: flex;
  gap: var(--sp-3);
  align-items: center;
  padding: var(--sp-3);
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--app-bg-card);

  &--active {
    border-color: var(--app-color-primary);
    background: var(--tint-primary);
  }

  &--void {
    color: var(--app-text-disabled);
    opacity: 0.7;
  }

  &__spec {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    font-size: var(--fs-body);
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__price {
    flex: none;
    font-weight: 600;
  }

  &__void {
    flex: none;
    color: var(--app-text-tertiary);
    font-size: var(--fs-note);
  }
}

// 与列表卡同为 1:1 裁切：原来列表 1:1、详情 1.71:1，同一张图点进来换了一种裁法，
// aspectFill 把桶装水这类竖长物体上下各切掉约 41%。圆角回到媒体档（--r-lg），
// 首屏媒体是铁律允许上阴影的三处之一，让它独占主卡地位。
.detail-cover {
  width: 100%;
  aspect-ratio: 1;
  border-radius: var(--r-lg);
  box-shadow: var(--sh-card);
}

.detail-cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--app-text-tertiary);
  background: var(--tint-neutral);
}

.detail-title-row {
  display: flex;
  gap: var(--sp-2);
  // 标题可折 2~3 行，tag 若居中会落进行间隙里、右侧空出 L 形；与首行顶齐
  align-items: flex-start;
  justify-content: space-between;
}

.detail-name {
  flex: 1;
  min-width: 0;
  font-size: 32rpx;
  font-weight: 600;
  word-break: break-all;
}

.detail-subtitle {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
}

.section-title {
  margin-bottom: 16rpx;
  font-size: 28rpx;
  font-weight: 600;
}

.sku-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}

.sku-detail {
  margin-top: 20rpx;
}

.price-row {
  display: flex;
  gap: var(--sp-3);
  align-items: baseline;
}

// 当前选中的这个价是整屏主数字：原来 36rpx=18px，与 SKU 行里那个继承来的 16px 只差 2px，
// 层级根本没拉开。字阶里 --fs-metric(24px) 正是为这一档准备的。
.sale-price {
  font-size: var(--fs-metric);
  font-weight: 700;
}

.market-price {
  font-size: var(--fs-caption);
  text-decoration: line-through;
}

.weight-line {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
}

.quantity-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 20rpx;
  font-size: 28rpx;
}

.desc-text {
  font-size: 26rpx;
  line-height: 1.7;
  word-break: break-all;
}
</style>
