<script setup lang="ts">
import type { CardSummary } from '@/api/card'
import type { CourierAdmission } from '@/api/delivery'
import type { OrderItem } from '@/api/order'
import { computed, ref, watch } from 'vue'
import { useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { deliveryApi } from '@/api/delivery'
import { orderApi } from '@/api/order'
import { useAccountStore } from '@/store/account'
import {
  CARD_STATUS_LABELS,
  CARD_STATUS_TONES,
  formatBizTimeShort,
  formatFen,
  formatMl,
  ORDER_STATUS_LABELS,
  ORDER_STATUS_TONES,
  ORDER_TYPE_LABELS,
} from '@/utils/format'
import { goTo } from '@/utils/navigation'
import { scanWaterCode } from '@/utils/scan'

/** 生活用水态：U01 的消费者视角（主态自适应三张脸之一）。 */
const props = defineProps<{ refreshTick: number }>()

const accountStore = useAccountStore()
const toast = useToast()

const hasCourierWork = computed(() => accountStore.hasCapability('COURIER_WORK'))

const loading = ref(true)
const primaryCard = ref<CardSummary | null>(null)
const recentOrders = ref<OrderItem[]>([])
const admission = ref<CourierAdmission | null>(null)
const scanning = ref(false)

/**
 * 无配送能力时的"成为配送员"引导（2026-07-16 决策）：
 * 配送身份可自助获取，按准入状态给出下一步；机主身份不可自助获取，无授权不显示任何入口。
 */
const courierGuidance = computed(() => {
  if (hasCourierWork.value) {
    return null
  }
  const status = admission.value?.status ?? 0
  if (status === 1) {
    return { title: '配送员申请审核中', desc: '等待运营审核，通过后首页将出现配送工作视角', action: '查看进度' }
  }
  if (status === 3) {
    return { title: '配送能力已停用', desc: '停用期间无法接单，如需恢复请联系运营', action: '查看详情' }
  }
  if (status === 4) {
    return { title: '配送员申请被驳回', desc: admission.value?.rejectReason || '可完善资料后重新提交', action: '重新申请' }
  }
  return { title: '成为配送员', desc: '提交准入申请，审核通过后可在服务范围内接单', action: '去申请' }
})

watch(() => props.refreshTick, refresh, { immediate: true })

async function refresh() {
  loading.value = true
  try {
    const [card, orders, admissionRecord] = await Promise.all([
      cardApi.getPrimaryCard(),
      orderApi.listMyOrders({ size: 3 }),
      deliveryApi.getCourierAdmission().catch(() => null),
    ])
    primaryCard.value = card
    recentOrders.value = orders.list
    admission.value = admissionRecord
  }
  finally {
    loading.value = false
  }
}

/** U01 原位扫码：失败原位提示并停留本页，成功以短期会话进入 U04（蓝图 §1）。 */
async function handleScan() {
  if (scanning.value) {
    return
  }
  scanning.value = true
  try {
    const session = await scanWaterCode()
    if (session) {
      goTo('U04', { scanSessionId: session.scanSessionId })
    }
  }
  catch (error) {
    toast.show(error instanceof Error ? error.message : '扫码解析失败，请重试')
  }
  finally {
    scanning.value = false
  }
}

function handleCardTap() {
  if (primaryCard.value) {
    goTo('U11', { cardId: primaryCard.value.cardId })
  }
  else {
    goTo('U10')
  }
}

function handleFutureEntry(name: string) {
  toast.show(`${name}为后续能力，本期未开放`)
}
</script>

<template>
  <view>
    <view class="page-section">
      <wd-card custom-class="home-card">
        <template #title>
          <view class="card-title-row">
            <view>我的水卡</view>
            <wd-tag v-if="primaryCard" :type="CARD_STATUS_TONES[primaryCard.cardStatus]" plain>
              {{ CARD_STATUS_LABELS[primaryCard.cardStatus] }}
            </wd-tag>
          </view>
        </template>
        <view v-if="primaryCard" class="card-summary" @click="handleCardTap">
          <view class="card-metric">
            <view class="card-metric-value">
              {{ formatFen(primaryCard.balanceFen) }}
            </view>
            <view class="muted-text">
              余额
            </view>
          </view>
          <view class="card-metric">
            <view class="card-metric-value">
              {{ formatMl(primaryCard.balanceMl) }}
            </view>
            <view class="muted-text">
              剩余水量
            </view>
          </view>
          <view class="card-metric card-metric-link">
            <wd-icon name="arrow-right" size="16px" />
            <view class="muted-text">
              卡详情
            </view>
          </view>
        </view>
        <view v-else class="card-empty" @click="handleCardTap">
          <view class="muted-text">
            当前账号暂无水卡，可查看充值与套餐契约
          </view>
          <wd-icon name="arrow-right" size="16px" />
        </view>
      </wd-card>
    </view>

    <view class="page-section">
      <wd-button block size="large" icon="scan" :loading="scanning" @click="handleScan">
        扫码取水
      </wd-button>
    </view>

    <view class="page-section">
      <wd-card title="用水服务" custom-class="home-card">
        <wd-grid :column="4" clickable bg-color="transparent">
          <wd-grid-item icon="location" text="附近水站" @itemclick="goTo('U07')" />
          <wd-grid-item icon="goods" text="配送订水" @itemclick="goTo('U08')" />
          <wd-grid-item icon="wallet" text="充值" @itemclick="goTo('U10')" />
          <wd-grid-item icon="usergroup" text="家庭资料" @itemclick="goTo('U13')" />
          <wd-grid-item icon="shop" @itemclick="handleFutureEntry('商城')">
            <template #text>
              <view class="future-entry-text">
                商城（后续）
              </view>
            </template>
          </wd-grid-item>
          <wd-grid-item icon="heart" @itemclick="handleFutureEntry('健康')">
            <template #text>
              <view class="future-entry-text">
                健康（后续）
              </view>
            </template>
          </wd-grid-item>
        </wd-grid>
      </wd-card>
    </view>

    <view v-if="courierGuidance" class="page-section">
      <wd-card custom-class="home-card">
        <view class="capability-row" @click="goTo('D02')">
          <view class="guidance-main">
            <view class="guidance-title">
              {{ courierGuidance.title }}
            </view>
            <view class="muted-text">
              {{ courierGuidance.desc }}
            </view>
          </view>
          <view class="capability-enter">
            {{ courierGuidance.action }} <wd-icon name="arrow-right" size="14px" />
          </view>
        </view>
      </wd-card>
    </view>

    <view class="page-section">
      <wd-card title="最近消费订单" custom-class="home-card">
        <template v-if="recentOrders.length">
          <wd-cell-group>
            <wd-cell
              v-for="item in recentOrders"
              :key="item.orderNo"
              :title="ORDER_TYPE_LABELS[item.orderType]"
              :label="`${item.orderNo} · ${formatBizTimeShort(item.createTime)}`"
              is-link
              @click="goTo('U06', { orderNo: item.orderNo })"
            >
              <view class="order-cell-value">
                <view>{{ formatFen(item.orderAmountFen) }}</view>
                <wd-tag :type="ORDER_STATUS_TONES[item.orderStatus]" plain>
                  {{ ORDER_STATUS_LABELS[item.orderStatus] }}
                </wd-tag>
              </view>
            </wd-cell>
          </wd-cell-group>
        </template>
        <wd-status-tip v-else-if="!loading" image="content" tip="暂无消费订单" />
        <view v-else class="muted-text">
          加载中…
        </view>
      </wd-card>
    </view>
  </view>
</template>

<style scoped lang="scss">
.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.card-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
}

.card-metric-value {
  font-size: 20px;
  font-weight: 600;
}

.card-metric-link {
  display: flex;
  flex-direction: column;
  align-items: center;
  color: var(--app-text-secondary);
}

.card-empty {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.capability-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 15px;
}

.capability-enter {
  display: flex;
  align-items: center;
  gap: 2px;
  color: var(--wot-color-theme, var(--app-color-primary));
  font-size: 13px;
  flex-shrink: 0;
}

.guidance-main {
  padding-right: 12px;
}

.guidance-title {
  margin-bottom: 4px;
  font-size: 15px;
  font-weight: 600;
}

.future-entry-text {
  margin-top: 8px;
  color: var(--app-text-secondary);
  font-size: 12px;
}

.order-cell-value {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}
</style>
