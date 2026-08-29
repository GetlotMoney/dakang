<script setup lang="ts">
import type { MallOrderStatus, MallOrderSummary } from '@/api/mall-trade'
import { onHide, onLoad, onReachBottom, onShow, onUnload } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { ContractError } from '@/api/common'
import { mallTradeApi } from '@/api/mall-trade'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import {
  formatBizTimeShort,
  formatFen,
  MALL_ORDER_STATUS_LABELS,
  MALL_ORDER_STATUS_TONES,
} from '@/utils/format'
import { goTo } from '@/utils/navigation'
import { createPagePoller } from '@/utils/page-poller'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '商城订单',
  },
})

/** 每页条数：与滚动加载配套，一次拉全量会在订单变多后越用越慢。 */
const PAGE_SIZE = 10

/** Tab 下标 → 状态筛选值，undefined 表示不过滤。 */
const STATUS_BY_TAB: (MallOrderStatus | undefined)[] = [undefined, 1, 2, 3, 4, 5]
const TAB_TITLES = ['全部', '待支付', '待发货', '配送中', '已完成', '已取消']

const activeTab = ref(0)
const loading = ref(true)
const errorMessage = ref('')
const orders = ref<MallOrderSummary[]>([])
const total = ref(0)
const loadingMore = ref(false)
const moreState = ref<'loading' | 'finished' | 'error'>('loading')
/** 下一页页码单独记，不用已加载条数推算：下单/取消会让分页错位，推算会重复页或漏页。 */
const nextPage = ref(2)
const failedCoverUrls = ref<Record<string, string>>({})

function coverVisible(item: MallOrderSummary): boolean {
  return !!item.firstCoverUrl && failedCoverUrls.value[item.orderNo] !== item.firstCoverUrl
}

function markCoverFailed(item: MallOrderSummary) {
  if (item.firstCoverUrl) {
    failedCoverUrls.value = { ...failedCoverUrls.value, [item.orderNo]: item.firstCoverUrl }
  }
}

// Wot 的 H5 sticky 会自行补 44px 导航高度；小程序端需显式避开状态栏与 44px 固定导航。
const stickyOffsetTop = (() => {
  let top = 0
  // #ifndef H5
  top = (uni.getWindowInfo().statusBarHeight ?? 0) + 44
  // #endif
  return top
})()

onLoad((query) => {
  const preset = typeof query?.orderStatus === 'string' ? Number(query.orderStatus) : Number.NaN
  const index = STATUS_BY_TAB.findIndex(value => value === preset)
  if (index > 0) {
    activeTab.value = index
  }
})

// 第一屏订单在页面可见时每5秒静默同步；加载过更多页后停用自动重排，仅保留 onShow 刷新。
const orderPoller = createPagePoller(() => reload(true), 5000)
onShow(() => {
  void reload()
  orderPoller.start()
})
onHide(orderPoller.stop)
onUnload(orderPoller.stop)

async function reload(silent = false) {
  if (silent && (loading.value || loadingMore.value || nextPage.value !== 2)) {
    return
  }
  if (!silent) {
    loading.value = true
    errorMessage.value = ''
  }
  try {
    const page = await mallTradeApi.listOrders({
      current: 1,
      size: PAGE_SIZE,
      orderStatus: STATUS_BY_TAB[activeTab.value],
    })
    orders.value = page.list
    total.value = page.total
    nextPage.value = 2
    moreState.value = orders.value.length >= total.value ? 'finished' : 'loading'
  }
  catch (error) {
    if (!silent) {
      orders.value = []
      total.value = 0
      errorMessage.value = error instanceof ContractError ? error.message : '订单加载失败，请重试'
    }
  }
  finally {
    if (!silent) {
      loading.value = false
    }
  }
}

/** 上拉加载下一页；重复订单号去重，空页即到底，避免翻页期间下单造成的错位与死循环。 */
async function loadMore() {
  if (loadingMore.value || loading.value || orders.value.length >= total.value) {
    return
  }
  loadingMore.value = true
  moreState.value = 'loading'
  try {
    const page = await mallTradeApi.listOrders({
      current: nextPage.value,
      size: PAGE_SIZE,
      orderStatus: STATUS_BY_TAB[activeTab.value],
    })
    nextPage.value += 1
    const known = new Set(orders.value.map(item => item.orderNo))
    orders.value = [...orders.value, ...page.list.filter(item => !known.has(item.orderNo))]
    total.value = page.total
    moreState.value = page.list.length === 0 || orders.value.length >= total.value
      ? 'finished'
      : 'loading'
  }
  catch {
    // 失败停在 error 态由用户点重试，不自动重发，也不静默吞掉这一页
    moreState.value = 'error'
  }
  finally {
    loadingMore.value = false
  }
}

onReachBottom(loadMore)

function openOrder(item: MallOrderSummary) {
  goTo('M06', { orderNo: item.orderNo })
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="商城订单" back-to="M01" />

    <view class="page-section filter-card">
      <wd-sticky :offset-top="stickyOffsetTop">
        <wd-tabs v-model="activeTab" slidable="always" @change="reload()">
          <wd-tab v-for="title in TAB_TITLES" :key="title" :title="title" />
        </wd-tabs>
      </wd-sticky>
    </view>

    <view v-if="loading" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, { width: '60%' }]" />
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain size="small" @click="reload()">
            重新加载
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <view v-else-if="!orders.length" class="page-section">
      <AppPageState state="empty" message="暂无订单">
        <template #actions>
          <wd-button size="small" @click="goTo('M01')">
            去挑商品
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else>
      <view
        v-for="item in orders"
        :key="item.orderNo"
        class="page-section order-card"
        @click="openOrder(item)"
      >
        <view class="order-head">
          <text class="muted-text">
            {{ item.orderNo }}
          </text>
          <wd-tag :type="MALL_ORDER_STATUS_TONES[item.orderStatus]" plain>
            {{ MALL_ORDER_STATUS_LABELS[item.orderStatus] }}
          </wd-tag>
        </view>
        <view class="order-body">
          <image
            v-if="coverVisible(item)"
            class="order-cover"
            :src="item.firstCoverUrl"
            mode="aspectFill"
            @error="markCoverFailed(item)"
          />
          <view v-else class="order-cover order-cover--empty">
            <wd-icon name="picture" size="22px" color="var(--app-text-disabled)" />
          </view>
          <view class="order-main">
            <text class="order-name">
              {{ item.firstProductName || '商城订单' }}
            </text>
            <text class="muted-text">
              共 {{ item.itemKindCount }} 种商品
            </text>
          </view>
          <text class="money order-amount">
            {{ formatFen(item.orderAmountFen) }}
          </text>
        </view>
        <view class="order-foot muted-text">
          <text>{{ formatBizTimeShort(item.createTime) }}</text>
          <text v-if="item.warehouseName">
            {{ item.warehouseName }}
          </text>
        </view>
      </view>

      <wd-loadmore :state="moreState" @reload="loadMore" />
    </template>
  </view>
</template>

<style lang="scss" scoped>
.filter-card {
  overflow: hidden;
  background: var(--app-bg-card);
  border-radius: var(--r-md);
}

.order-card {
  padding: 12px;
  background: var(--app-bg-card);
  border-radius: var(--r-md);
}

.order-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.order-body {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-top: 8px;
}

.order-cover {
  flex: none;
  width: 56px;
  height: 56px;
  border-radius: var(--r-sm);

  &--empty {
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--app-bg-page);
  }
}

.order-main {
  flex: 1;
  min-width: 0;
}

.order-name {
  display: block;
  overflow: hidden;
  font-size: var(--fs-body);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-amount {
  font-size: var(--fs-title);
  font-weight: 700;
}

.order-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
}
</style>
