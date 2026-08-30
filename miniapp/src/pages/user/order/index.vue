<script setup lang="ts">
import type { OrderItem, OrderStatus, OrderType } from '@/api/order'
import AppPageState from '@/components/app-page-state.vue'
import { onHide, onShow, onUnload } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { ContractError } from '@/api/common'
import { orderApi } from '@/api/order'
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
import { createPagePoller } from '@/utils/page-poller'
import { getWxSafeHeader } from '@/utils/safe-area'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '订单',
  },
})

const safeHeader = getWxSafeHeader()
const accountStore = useAccountStore()
/** Tab 下标 → orderType 契约值：全部/取水/充值/配送，undefined 表示不过滤。 */
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
// 常驻订单 Tab 需要追踪设备、支付与配送异步状态；页面可见时每4秒静默刷新，隐藏即停。
const orderPoller = createPagePoller(() => refresh(true), 4000)
onShow(() => {
  void refresh()
  orderPoller.start()
})
onHide(orderPoller.stop)
onUnload(orderPoller.stop)

async function refresh(silent = false) {
  if (silent && loading.value) {
    return
  }
  if (!accountStore.restored) {
    await accountStore.restoreSession().catch(() => null)
  }
  if (!accountStore.context) {
    // loading 初值为 true，早退时必须落下来，否则登录未完成时切到本 Tab 会永远停在骨架上
    loading.value = false
    return
  }
  await loadOrders(silent)
}

/** 筛选“改变即查询”：Tab 切换与状态选择均直接触发本函数，不设查询按钮。 */
async function loadOrders(silent = false) {
  if (!silent) {
    loading.value = true
    errorMessage.value = ''
  }
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
    orders.value = result.list
  }
  catch (error) {
    if (!silent) {
      orders.value = []
      errorMessage.value = error instanceof ContractError ? error.message : '订单加载失败，请重试'
    }
  }
  finally {
    if (!silent) {
      loading.value = false
    }
  }
}

function openOrder(item: OrderItem) {
  goTo('U06', { orderNo: item.orderNo })
}

/** 状态 chip 点击：同一枚再点一次不取消（「全部状态」本身就是取消）。 */
function pickStatus(value: number) {
  if (statusFilter.value === value) {
    return
  }
  statusFilter.value = value
  loadOrders()
}

/** 是否处在筛选态。空态必须区分「你没有订单」和「这个筛选条件下没有」，恒空组合下写「暂无订单」是误导。 */
const filtering = computed(
  () => activeTab.value !== 0 || statusFilter.value !== ORDER_STATUS_ALL,
)

function resetFilters() {
  activeTab.value = 0
  statusFilter.value = ORDER_STATUS_ALL
  loadOrders()
}

/** 一行摘要（用户辨认「这是哪一单」的唯一字段）：充值单不拼内部主键 cardId，按套餐名 → 卡号 → 通用词回落。 */
function orderSummary(item: OrderItem): string {
  if (item.orderType === 2) {
    return item.recharge?.packageName
      || (item.recharge?.cardNo ? `水卡 ${item.recharge.cardNo}` : '')
      || '购卡充值订单'
  }
  const nodes = [item.stationName, item.deviceNo].filter(Boolean) as string[]
  if (item.orderType === 3) {
    nodes.push('配送上门')
  }
  return nodes.length ? nodes.join(' · ') : '—'
}

/** 水量摘要：常态只报实取一个数，实取与计划不一致时才把两个都摆出来。 */
function volumeSummary(item: OrderItem): string {
  if (item.planMl === undefined) {
    return ''
  }
  if (item.actualMl === undefined) {
    return `计划 ${formatMl(item.planMl)}`
  }
  return item.actualMl === item.planMl
    ? `实取 ${formatMl(item.actualMl)}`
    : `实取 ${formatMl(item.actualMl)} / 计划 ${formatMl(item.planMl)}`
}

/** 已结清订单降权：只压第一行的视觉重量，不给整行加 opacity（次要行再乘透明度就读不出来了）。 */
const SETTLED_STATUSES: ReadonlySet<number> = new Set([4, 5, 7])
function isSettled(item: OrderItem): boolean {
  return SETTLED_STATUSES.has(item.orderStatus)
}

/**
 * 需要用户动手的单上浅底：待支付(1) 用 warning，异常待补偿(6) 用 danger。
 * 其余一律不上底——状态色的第二落点只给这两档，避免整屏花。
 */
function attentionTone(item: OrderItem): '' | 'pay' | 'fault' {
  return item.orderStatus === 1 ? 'pay' : item.orderStatus === 6 ? 'fault' : ''
}
</script>

<template>
  <view class="page-shell top-level-page" :style="{ paddingTop: safeHeader.pageTopPadding }">
    <view class="order-header">
      <view class="page-title">
        我的订单
      </view>
    </view>

    <!-- 订单类型是页内一级切换，标签可收缩或横向滑动；订单状态留给下方左侧筛选栏，
         避免两个独立维度争抢同一条横向空间。 -->
    <view class="filter">
      <wd-tabs v-model="activeTab" slidable="always" @change="loadOrders">
        <wd-tab v-for="title in ORDER_TAB_TITLES" :key="title" :title="title" />
      </wd-tabs>
    </view>

    <!-- 类型是一级导航，状态是同页过滤维度。移动端不把两个维度都塞进横向栏：
         状态固定在左侧，订单内容占右侧；长状态名自然换行，不再依赖横向滑动。 -->
    <view class="order-workspace">
      <view class="status-rail">
        <view
          v-for="column in statusColumns"
          :key="column.value"
          class="status-rail__item"
          :class="{ 'status-rail__item--active': statusFilter === column.value }"
          @click="pickStatus(column.value)"
        >
          {{ column.label }}
        </view>
      </view>

      <view class="order-content">
        <!-- 骨架与真实列表同构，避免返回/切 Tab 时整块白底闪没再弹回 -->
        <view v-if="loading" class="order-list order-list--skeleton">
          <AppPageState
            state="loading"
            :row-col="[1, { width: '60%' }, 1, { width: '60%' }, 1, { width: '60%' }]"
          />
        </view>

        <view v-else-if="errorMessage" class="order-panel">
          <AppPageState state="error" :message="errorMessage">
            <template #actions>
              <wd-button size="small" plain @click="loadOrders">
                重新加载
              </wd-button>
            </template>
          </AppPageState>
        </view>

        <view v-else-if="!orders.length" class="order-panel">
          <!-- 用 title 而不是 message，避免与默认标题连出两句同义空话 -->
          <AppPageState state="empty" :title="filtering ? '当前筛选无订单' : '暂无订单'">
            <template v-if="filtering" #actions>
              <wd-button size="small" plain @click="resetFilters">
                查看全部订单
              </wd-button>
            </template>
          </AppPageState>
        </view>

        <!-- 一单两行：第一行「类型/金额/状态」，第二行「摘要·水量·时间」；订单号不上列表，详情页查 -->
        <view v-else class="order-list">
          <view
            v-for="item in orders"
            :key="item.orderNo"
            class="order-row pressable"
            :class="[
              { 'order-row--settled': isSettled(item) },
              attentionTone(item) ? `order-row--${attentionTone(item)}` : '',
            ]"
            @click="openOrder(item)"
          >
            <view class="order-row__top">
              <text class="order-row__type">
                {{ ORDER_TYPE_LABELS[item.orderType] }}
              </text>
              <text class="order-row__amount money">
                {{ formatFen(item.orderAmountFen) }}
              </text>
              <wd-tag :type="ORDER_STATUS_TONES[item.orderStatus]" plain>
                {{ ORDER_STATUS_LABELS[item.orderStatus] }}
              </wd-tag>
            </view>
            <!-- 摘要在前、时间靠右：三段里只有摘要能被压缩 -->
            <view class="order-row__meta">
              <text class="order-row__summary">
                {{ orderSummary(item) }}
              </text>
              <text v-if="volumeSummary(item)" class="order-row__volume num">
                {{ volumeSummary(item) }}
              </text>
              <text class="order-row__time num">
                {{ formatBizTimeShort(item.createTime) }}
              </text>
            </view>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.top-level-page {
  padding-top: calc(env(safe-area-inset-top) + 24px);
}

.page-title {
  font-size: var(--fs-metric);
  font-weight: 700;
}

.filter {
  margin-top: var(--gap-block);
  overflow: hidden;
  border-radius: var(--r-sm) var(--r-sm) 0 0;
  background: var(--app-bg-card);
}

.order-workspace {
  display: flex;
  gap: var(--sp-3);
  align-items: flex-start;
  margin-top: var(--gap-block);
}

.status-rail {
  flex: 0 0 84px;
  overflow: hidden;
  border-radius: var(--r-sm);
  background: var(--app-bg-card);

  &__item {
    position: relative;
    display: flex;
    min-height: 44px;
    box-sizing: border-box;
    align-items: center;
    padding: var(--sp-2) var(--sp-2) var(--sp-2) var(--sp-3);
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
    line-height: 1.3;

    &--active {
      color: var(--app-color-primary);
      background: var(--tint-primary);
      font-weight: 600;

      &::before {
        position: absolute;
        top: var(--sp-2);
        bottom: var(--sp-2);
        left: 0;
        width: 3px;
        border-radius: 0 var(--r-pill) var(--r-pill) 0;
        background: var(--app-color-primary);
        content: '';
      }
    }
  }
}

.order-content {
  flex: 1;
  min-width: 0;
}

.order-panel {
  overflow: hidden;
  border-radius: var(--r-md);
  background: var(--app-bg-card);
}

// 整份列表是一张连续的白底表，不是一摞卡：行与行只用中性分隔线，
// 不给每行各配圆角、描边和阴影——那会把「找订单」变成「逛卡片展」。
.order-list {
  overflow: hidden;
  border-radius: var(--r-md);
  background: var(--app-bg-card);

  &--skeleton {
    padding: 0 var(--sp-4);
  }
}

.order-row {
  padding: var(--sp-4);
  border-bottom: 1px solid var(--line-1);

  &:last-child {
    border-bottom: none;
  }

  // 需处理的两档上浅底；正常态一律不上底，避免整屏花
  &--pay {
    background: var(--tint-warning);
  }

  &--fault {
    background: var(--tint-danger);
  }

  // 已结清只降第一行的重量，不用整行 opacity
  &--settled {
    .order-row__type {
      color: var(--app-text-secondary);
    }

    .order-row__amount {
      color: var(--app-text-secondary);
      font-weight: 500;
    }
  }

  &__top {
    display: flex;
    gap: var(--sp-2);
    align-items: center;
  }

  // display:block 不能省：<text> 默认 inline，inline 上 text-overflow 不生效（微信渲染层不保证 blockify 兜底）
  &__type {
    display: block;
    flex: 1;
    min-width: 0;
    overflow: hidden;
    font-size: var(--fs-body);
    font-weight: 500;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__amount {
    flex: none;
    font-size: var(--fs-title);
    font-weight: 700;
  }

  &__meta {
    display: flex;
    gap: var(--sp-2);
    align-items: baseline;
    margin-top: var(--sp-2);
    color: var(--app-text-tertiary);
    font-size: var(--fs-note);
  }

  // 站点/设备名长度不可控：独吞剩余宽度并单行截断，保证时间与水量不被挤到第三行
  &__summary {
    display: block;
    flex: 1;
    min-width: 0;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__volume,
  &__time {
    flex: none;
  }
}
</style>
