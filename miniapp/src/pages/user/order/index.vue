<script setup lang="ts">
import type { OrderItem, OrderStatus, OrderType } from '@/api/order'
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { ContractError } from '@/api/common'
import { orderApi } from '@/api/order'
import { listLocalRechargeOrders } from '@/api/recharge'
import { currentMode } from '@/api/runtime'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import { useAccountStore } from '@/store/account'
import {
  formatBizTimeShort,
  formatFen,
  formatMl,
  ORDER_STATUS_LABELS,
  ORDER_STATUS_TONES,
  ORDER_TYPE_LABELS,
} from '@/utils/format'
import { goTo } from '@/utils/navigation'
import { getWxSafeHeader } from '@/utils/safe-area'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '订单',
  },
})

const safeHeader = getWxSafeHeader()
const accountStore = useAccountStore()
const isOrderReal = currentMode('order') === 'real'
const orderListNotice = isOrderReal
  ? '订单主列表来自真实 order 接口；本地充值 Mock/固定快照逐项标识。扫码取水扣款事实以真实订单详情为准。'
  : '订单主列表与充值补充项均为 Mock/固定快照，不代表本次发生真实支付、扣款、出水或配送。'

/** Tab 下标 → orderType 契约值，undefined 表示不过滤（蓝图 §6.2：全部/取水/充值/配送）。 */
const ORDER_TYPE_BY_TAB: (OrderType | undefined)[] = [undefined, 1, 2, 3]
const ORDER_TAB_TITLES = ['全部', '取水', '充值', '配送']

/** 状态筛选列：全部 + PC 字典 1341 八状态，文案与色调复用 utils/format 统一口径。 */
const ORDER_STATUS_ALL = 0
const statusColumns = [
  { label: '全部状态', value: ORDER_STATUS_ALL },
  ...Object.keys(ORDER_STATUS_LABELS).map(key => ({
    label: ORDER_STATUS_LABELS[Number(key) as OrderStatus],
    value: Number(key),
  })),
]

const activeTab = ref(0)
const statusFilter = ref(ORDER_STATUS_ALL)
const loading = ref(true)
const errorMessage = ref('')
const orders = ref<OrderItem[]>([])
const localMockOrderNos = ref<Set<string>>(new Set())

// onShow 刷新：从 U08/U10 下单返回或从 U06 返回时，本 Tab 立即可见最新订单。
onShow(refresh)

async function refresh() {
  if (!accountStore.restored) {
    await accountStore.restoreSession().catch(() => null)
  }
  if (!accountStore.context) {
    return
  }
  await loadOrders()
}

/** 筛选“改变即查询”（蓝图 §6.7）：Tab 切换与状态选择均直接触发本函数，不设查询按钮。 */
async function loadOrders() {
  loading.value = true
  errorMessage.value = ''
  try {
    const orderTypeFilter = ORDER_TYPE_BY_TAB[activeTab.value]
    const statusValue = statusFilter.value === ORDER_STATUS_ALL
      ? undefined
      : (statusFilter.value as OrderStatus)
    const result = await orderApi.listMyOrders({
      orderType: orderTypeFilter,
      orderStatus: statusValue,
      size: 50,
    })
    let list = result.list
    // recharge 域为 mock 时，充值单仅存在于前端 scenarioStore，不进真实 order 后端。
    // 「全部/充值」Tab 下合并本地 mock 充值单，逐项由 order.mockMeta 在列表标注数据源（2026-07-20 收口复审）。
    if (currentMode('recharge') === 'mock' && (orderTypeFilter === undefined || orderTypeFilter === 2)) {
      const seen = new Set(list.map(item => item.orderNo))
      const localRecharge = listLocalRechargeOrders()
        .map(detail => detail.order)
        .filter(order => !seen.has(order.orderNo)
          && (statusValue === undefined || order.orderStatus === statusValue))
      localMockOrderNos.value = new Set(localRecharge.map(order => order.orderNo))
      list = [...localRecharge, ...list].sort((a, b) => b.createTime.localeCompare(a.createTime))
    }
    else {
      localMockOrderNos.value = new Set()
    }
    orders.value = list
  }
  catch (error) {
    orders.value = []
    localMockOrderNos.value = new Set()
    errorMessage.value = error instanceof ContractError ? error.message : '订单加载失败，请重试'
  }
  finally {
    loading.value = false
  }
}

function isLocalMockOrder(item: OrderItem): boolean {
  return localMockOrderNos.value.has(item.orderNo)
}

function openOrder(item: OrderItem) {
  goTo('U06', isLocalMockOrder(item)
    ? { orderNo: item.orderNo, source: 'local-mock' }
    : { orderNo: item.orderNo })
}

/** 站点/设备或配送摘要（蓝图 §6.7 U02 冻结字段：订单号、摘要、金额、水量、时间）。 */
function orderSummary(item: OrderItem): string {
  if (item.orderType === 2) {
    return item.cardId ? `充值水卡 ${item.cardId}` : '购卡充值订单'
  }
  const nodes = [item.stationName, item.deviceNo].filter(Boolean) as string[]
  if (item.orderType === 3) {
    nodes.push('配送上门')
  }
  return nodes.length ? nodes.join(' · ') : '—'
}
</script>

<template>
  <view class="page-shell top-level-page" :style="{ paddingTop: safeHeader.pageTopPadding }">
    <view class="order-header">
      <view class="page-title">
        我的订单
      </view>
      <view class="page-description">
        仅本账号作为消费者的订单，不含配送任务与经营流水
      </view>
    </view>

    <AppPrototypeNotice :text="orderListNotice" />

    <view class="page-section filter-card">
      <wd-tabs v-model="activeTab" @change="loadOrders">
        <wd-tab v-for="title in ORDER_TAB_TITLES" :key="title" :title="title" />
      </wd-tabs>
      <wd-picker
        v-model="statusFilter"
        :columns="statusColumns"
        label="订单状态"
        title="选择订单状态"
        @confirm="loadOrders"
      />
    </view>

    <view v-if="loading" class="page-section muted-text">
      订单加载中…
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <wd-status-tip image="network" :tip="errorMessage">
        <template #bottom>
          <view class="status-actions">
            <wd-button size="small" plain @click="loadOrders">
              重新加载
            </wd-button>
          </view>
        </template>
      </wd-status-tip>
    </view>

    <view v-else-if="!orders.length" class="page-section">
      <wd-status-tip image="content" tip="暂无订单" />
    </view>

    <view v-else class="page-section">
      <view
        v-for="item in orders"
        :key="item.orderNo"
        class="order-item"
        @click="openOrder(item)"
      >
        <wd-card>
          <template #title>
            <view class="card-title-row">
              <view class="card-title-left">
                <view>{{ ORDER_TYPE_LABELS[item.orderType] }}</view>
                <wd-tag :type="isLocalMockOrder(item) ? 'warning' : 'success'" plain size="small">
                  {{ isLocalMockOrder(item) ? '本地充值 Mock' : (isOrderReal ? '真实接口' : '订单 Mock/快照') }}
                </wd-tag>
              </view>
              <wd-tag :type="ORDER_STATUS_TONES[item.orderStatus]" plain>
                {{ ORDER_STATUS_LABELS[item.orderStatus] }}
              </wd-tag>
            </view>
          </template>
          <view class="order-line muted-text">
            {{ item.orderNo }}
          </view>
          <view class="order-line">
            {{ orderSummary(item) }}
          </view>
          <view class="order-line order-amount-row">
            <view class="order-amount">
              {{ formatFen(item.orderAmountFen) }}
            </view>
            <view v-if="item.planMl !== undefined" class="muted-text">
              计划 {{ formatMl(item.planMl) }}<template v-if="item.actualMl !== undefined">
                / 实际 {{ formatMl(item.actualMl) }}
              </template>
            </view>
          </view>
          <view class="order-line muted-text">
            {{ formatBizTimeShort(item.createTime) }}
          </view>
        </wd-card>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.top-level-page {
  padding-top: calc(env(safe-area-inset-top) + 24px);
}

.page-title {
  font-size: 24px;
  font-weight: 600;
}

.page-description {
  margin-top: 8px;
  color: var(--app-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.filter-card {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}

.status-actions {
  display: flex;
  justify-content: center;
  margin-top: 16px;
  width: 100%;
}

.order-item {
  margin-top: 12px;
}

.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.card-title-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.order-line {
  margin-top: 4px;
  font-size: 14px;
}

.order-amount-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.order-amount {
  font-size: 18px;
  font-weight: 600;
}
</style>
