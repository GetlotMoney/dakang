<script setup lang="ts">
import type { MallCartLine } from '@/api/mall-trade'
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { ContractError } from '@/api/common'
import { encodeCheckoutLines, MALL_LINE_LIMITS, mallTradeApi } from '@/api/mall-trade'
import AppBottomActionBar from '@/components/app-bottom-action-bar.vue'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import { formatFen, formatSpecs } from '@/utils/format'
import { goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '购物车',
  },
})

const toast = useToast()
const message = useMessage()

const loading = ref(true)
const errorMessage = ref('')
const lines = ref<MallCartLine[]>([])
/** 勾选的 SKU；失效行永不入选，合计与结算都只看这份集合。 */
const selectedSkuIds = ref<string[]>([])
/** 是否已完成首次装车：决定勾选态是「默认全选」还是「保留用户选择」。 */
const initialized = ref(false)
/** 正在写入的行：同一时刻只允许一行改量/移除，避免并发写把数量改回旧值。 */
const pendingSkuId = ref('')
const failedCoverUrls = ref<Record<string, string>>({})

function coverVisible(line: MallCartLine): boolean {
  return !!line.coverUrl && failedCoverUrls.value[line.skuId] !== line.coverUrl
}

function markCoverFailed(line: MallCartLine) {
  if (line.coverUrl) {
    failedCoverUrls.value = { ...failedCoverUrls.value, [line.skuId]: line.coverUrl }
  }
}

onShow(refresh)

async function refresh() {
  loading.value = true
  errorMessage.value = ''
  try {
    applyCart(await mallTradeApi.cartList())
  }
  catch (error) {
    lines.value = []
    selectedSkuIds.value = []
    errorMessage.value = error instanceof ContractError ? error.message : '购物车加载失败，请稍后重试'
  }
  finally {
    loading.value = false
  }
}

/**
 * 每次写操作后都用服务端返回的整车覆盖本地，不做局部打补丁：
 * 价格与可售状态是实时读取的，隔壁行可能在这次请求里刚刚失效。
 *
 * 勾选态的取舍：首次进入默认全选有效行；之后保留用户自己的勾选，只把失效行剔出去，
 * 期间新加入车里的行默认勾上。失效行任何情况下都不入选，因此不会混进结算与合计。
 */
function applyCart(cart: { lines: MallCartLine[] }) {
  const purchasable = cart.lines.filter(line => line.purchasable).map(line => line.skuId)
  const known = new Set(lines.value.map(line => line.skuId))
  const kept = new Set(selectedSkuIds.value)
  selectedSkuIds.value = initialized.value
    ? purchasable.filter(skuId => kept.has(skuId) || !known.has(skuId))
    : purchasable
  initialized.value = true
  lines.value = cart.lines
}

const selectedLines = computed(() =>
  lines.value.filter(line => line.purchasable && selectedSkuIds.value.includes(line.skuId)),
)

const selectedQuantity = computed(() =>
  selectedLines.value.reduce((sum, line) => sum + line.quantity, 0),
)

const selectedAmountFen = computed(() =>
  selectedLines.value.reduce((sum, line) => sum + line.itemAmountFen, 0),
)

const allSelected = computed(() => {
  const purchasable = lines.value.filter(line => line.purchasable)
  return purchasable.length > 0 && purchasable.length === selectedLines.value.length
})

function toggleAll() {
  selectedSkuIds.value = allSelected.value
    ? []
    : lines.value.filter(line => line.purchasable).map(line => line.skuId)
}

async function handleQuantityChange(line: MallCartLine, next: number) {
  if (
    pendingSkuId.value
    || !Number.isSafeInteger(next)
    || next < 1
    || next > MALL_LINE_LIMITS.maxQuantity
    || next === line.quantity
  ) {
    return
  }
  pendingSkuId.value = line.skuId
  try {
    // increment=false：购物车页是「改成几件」，累加语义只属于商品详情页的加购
    applyCart(await mallTradeApi.cartSave({ skuId: line.skuId, quantity: next, increment: false }))
  }
  catch (error) {
    toast.show(error instanceof ContractError ? error.message : '数量修改失败，请重试')
    // 失败后回读服务端真值，页面上不留一个没写进去的数字
    await refresh()
  }
  finally {
    pendingSkuId.value = ''
  }
}

function handleRemove(line: MallCartLine) {
  message
    .confirm({ title: '移除商品', msg: `确认移除「${line.productName || line.skuName}」？` })
    .then(async () => {
      if (pendingSkuId.value) {
        return
      }
      pendingSkuId.value = line.skuId
      try {
        selectedSkuIds.value = selectedSkuIds.value.filter(skuId => skuId !== line.skuId)
        applyCart(await mallTradeApi.cartDelete(line.skuId))
        toast.success('已移除')
      }
      catch (error) {
        toast.show(error instanceof ContractError ? error.message : '移除失败，请重试')
      }
      finally {
        pendingSkuId.value = ''
      }
    })
    .catch(() => null)
}

function openProduct(line: MallCartLine) {
  if (line.productId) {
    goTo('M02', { productId: line.productId })
  }
}

function handleCheckout() {
  if (!selectedLines.value.length || pendingSkuId.value) {
    return
  }
  try {
    goTo('M04', {
      lines: encodeCheckoutLines(
        selectedLines.value.map(line => ({ skuId: line.skuId, quantity: line.quantity })),
      ),
    })
  }
  catch (error) {
    toast.show(error instanceof ContractError ? error.message : '暂时无法结算，请重试')
  }
}
</script>

<template>
  <view
    class="page-shell"
    :class="{ 'page-shell--with-bar': !loading && !errorMessage && lines.length > 0 }"
  >
    <AppNavbar title="购物车" back-to="M01" />
    <wd-toast />
    <wd-message-box />

    <view v-if="loading" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, 1, { width: '70%' }]" />
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain size="small" @click="refresh">
            重新加载
          </wd-button>
          <wd-button plain size="small" @click="goTo('M01')">
            去逛逛
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <view v-else-if="!lines.length" class="page-section">
      <AppPageState state="empty" empty-icon="cart" message="购物车还是空的">
        <template #actions>
          <wd-button size="small" @click="goTo('M01')">
            去挑商品
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else>
      <wd-checkbox-group v-model="selectedSkuIds">
        <view
          v-for="line in lines"
          :key="line.skuId"
          class="page-section cart-line"
          :class="{ 'cart-line-void': !line.purchasable }"
        >
          <view class="line-select">
            <wd-checkbox :model-value="line.skuId" :disabled="!line.purchasable" />
          </view>
          <image
            v-if="coverVisible(line)"
            class="line-cover"
            :src="line.coverUrl"
            mode="aspectFill"
            @error="markCoverFailed(line)"
            @click="openProduct(line)"
          />
          <view v-else class="line-cover line-cover-placeholder">
            <text>暂无图片</text>
          </view>
          <view class="line-body">
            <text class="line-name" @click="openProduct(line)">
              {{ line.productName || '该商品已不可购买' }}
            </text>
            <text class="line-spec muted-text">
              {{ formatSpecs(line.specs, line.skuName) }}
            </text>
            <view v-if="!line.purchasable" class="line-void-row">
              <wd-tag type="default" plain>
                {{ line.unavailableReason || '该商品已下架' }}
              </wd-tag>
              <wd-button size="small" plain type="error" @click="handleRemove(line)">
                移除
              </wd-button>
            </view>
            <view v-else class="line-price-row">
              <text class="money line-price">
                {{ formatFen(line.salePriceFen) }}
              </text>
              <wd-input-number
                :model-value="line.quantity"
                :min="1"
                :max="MALL_LINE_LIMITS.maxQuantity"
                :disabled="pendingSkuId === line.skuId"
                @change="(e) => handleQuantityChange(line, Number(e.value))"
              />
            </view>
            <view v-if="pendingSkuId === line.skuId" class="line-pending">
              <wd-loading size="14px" />
              <text>数量更新中</text>
            </view>
            <view v-if="line.purchasable" class="line-sub-row">
              <text class="muted-text">
                小计 {{ formatFen(line.itemAmountFen) }}
              </text>
              <wd-button size="small" plain type="error" @click="handleRemove(line)">
                移除
              </wd-button>
            </view>
          </view>
        </view>
      </wd-checkbox-group>

      <AppBottomActionBar bordered>
        <template #summary>
          <view class="settle-left" @click="toggleAll">
            <wd-checkbox :model-value="allSelected" />
            <text>全选</text>
          </view>
        </template>
        <template #secondary>
          <view class="settle-amount">
            <text class="muted-text">
              合计
            </text>
            <text class="money settle-total">
              {{ formatFen(selectedAmountFen) }}
            </text>
          </view>
        </template>
        <template #primary>
          <wd-button
            type="primary"
            :disabled="!selectedLines.length || !!pendingSkuId"
            @click="handleCheckout"
          >
            结算（{{ selectedQuantity }}）
          </wd-button>
        </template>
      </AppBottomActionBar>
    </template>
  </view>
</template>

<style lang="scss" scoped>
// 数量在途：行内明示。只禁用步进器与结算按钮不够——用户看不出为什么点不动。
.line-pending {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
  margin-top: var(--sp-1);
  color: var(--app-text-secondary);
  font-size: var(--fs-note);
}

.cart-line {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 12px;
  background: var(--app-bg-card);
  border-radius: var(--r-md);
}

.cart-line-void {
  opacity: 0.55;
}

.line-select {
  align-self: center;
}

.line-cover {
  flex: none;
  width: 72px;
  height: 72px;
  border-radius: var(--r-sm);
}

.line-cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--fs-note);
  color: var(--app-text-tertiary);
  background: var(--app-bg-page);
}

.line-body {
  flex: 1;
  min-width: 0;
}

.line-name {
  display: block;
  overflow: hidden;
  font-size: var(--fs-body);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.line-spec {
  display: block;
  margin-top: 4px;
  font-size: var(--fs-caption);
}

.line-price-row,
.line-sub-row,
.line-void-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.line-price {
  font-size: var(--fs-title);
  font-weight: 600;
}

.settle-left {
  display: flex;
  gap: 4px;
  align-items: center;
  font-size: var(--fs-body);
}

.settle-amount {
  display: flex;
  gap: 4px;
  align-items: baseline;
}

.settle-total {
  font-size: var(--fs-metric);
  font-weight: 700;
}
</style>
