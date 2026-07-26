<script setup lang="ts">
import type { DeviceSummary, OwnerTransactionItem } from '@/api/device'
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref, watch } from 'vue'
import { ContractError } from '@/api/common'
import { deviceApi } from '@/api/device'
import AppNavbar from '@/components/app-navbar.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import {
  formatBizTimeShort,
  formatFen,
  formatMl,
  ORDER_STATUS_LABELS,
  ORDER_STATUS_TONES,
  ORDER_TYPE_LABELS,
} from '@/utils/format'
import { backOr } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '交易快照',
  },
})

/** 周期简化为两档；近7日基于原型固定当前时间 2026-07-16 推算（Store now=20260716180000）。 */
const RECENT_7D_START = '20260709000000'
const PERIOD_COLUMNS = [
  { label: '全部', value: '' },
  { label: '近7日', value: '7d' },
]

const loading = ref(true)
const errorMessage = ref('')
const devices = ref<DeviceSummary[]>([])
const items = ref<OwnerTransactionItem[]>([])
const total = ref(0)
const deviceFilter = ref('')
const periodFilter = ref('')

let initialized = false
let requestToken = 0

const deviceColumns = computed(() => [
  { label: '全部设备', value: '' },
  ...devices.value.map(item => ({
    label: `${item.deviceNo} · ${item.deviceName}`,
    value: item.deviceNo,
  })),
])

// 汇总为本页就地累加，只读展示，不做提现或分账计算（蓝图 §3.2）。
const summary = computed(() => ({
  count: items.value.length,
  volumeMl: items.value.reduce((sum, item) => sum + (item.actualVolumeMl ?? 0), 0),
  amountFen: items.value.reduce((sum, item) => sum + item.orderAmountFen, 0),
}))

// 筛选“改变即查询”（蓝图 §6.7）；初始化参数写入不重复触发。
watch([deviceFilter, periodFilter], () => {
  if (initialized) {
    void loadTransactions()
  }
})

onLoad(async (options) => {
  deviceFilter.value = String(options?.deviceNo ?? '')
  periodFilter.value = String(options?.period ?? '') === '7d' ? '7d' : ''
  try {
    devices.value = await deviceApi.listOwnerDevices()
  }
  catch (error) {
    loading.value = false
    errorMessage.value = error instanceof ContractError ? error.message : '授权设备加载失败，请稍后重试'
    return
  }
  await loadTransactions()
  initialized = true
})

async function loadTransactions() {
  const token = ++requestToken
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await deviceApi.listOwnerTransactions({
      periodStart: periodFilter.value === '7d' ? RECENT_7D_START : undefined,
      deviceNo: deviceFilter.value || undefined,
      size: 50,
    })
    if (token !== requestToken) {
      return
    }
    items.value = result.list
    total.value = result.total
  }
  catch (error) {
    if (token !== requestToken) {
      return
    }
    items.value = []
    total.value = 0
    errorMessage.value = error instanceof ContractError ? error.message : '交易快照加载失败，请稍后重试'
  }
  finally {
    if (token === requestToken) {
      loading.value = false
    }
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="交易快照" back-to="O01" />
    <AppPrototypeNotice />

    <view v-if="errorMessage" class="page-section">
      <wd-status-tip image="network" :tip="errorMessage">
        <template #bottom>
          <view class="status-actions">
            <wd-button plain size="small" @click="backOr('O01')">
              返回
            </wd-button>
          </view>
        </template>
      </wd-status-tip>
    </view>

    <template v-else>
      <view class="page-section">
        <wd-cell-group border>
          <wd-picker
            v-model="deviceFilter"
            label="设备"
            :columns="deviceColumns"
          />
          <wd-picker
            v-model="periodFilter"
            label="周期"
            :columns="PERIOD_COLUMNS"
          />
        </wd-cell-group>
        <view class="muted-text filter-note">
          近7日按原型固定当前时间 2026-07-16 推算；筛选仅在授权范围内过滤。
        </view>
      </view>

      <view v-if="loading" class="page-section muted-text">
        加载中…
      </view>

      <template v-else>
        <view class="page-section summary-card">
          <view class="summary-line">
            本页订单 {{ summary.count }} 单 · 出水 {{ formatMl(summary.volumeMl) }} · 金额 {{ formatFen(summary.amountFen) }}
          </view>
          <view class="muted-text">
            汇总为本页就地累加的只读口径
          </view>
          <view v-if="total > items.length" class="muted-text">
            共 {{ total }} 笔，当前展示前 {{ items.length }} 笔
          </view>
          <view class="muted-text summary-note">
            仅站点经营快照，不含用户订单明细，不可跳转用户订单；不计算提现或分账。
          </view>
        </view>

        <view v-if="!items.length" class="page-section">
          <wd-status-tip image="content" tip="当前条件下暂无交易快照" />
        </view>

        <template v-else>
          <view
            v-for="item in items"
            :key="item.orderNo"
            class="page-section txn-card"
          >
            <view class="txn-head">
              <view class="txn-no">
                {{ item.orderNo }}
              </view>
              <view class="txn-tags">
                <wd-tag plain>
                  {{ ORDER_TYPE_LABELS[item.orderType] }}
                </wd-tag>
                <wd-tag :type="ORDER_STATUS_TONES[item.orderStatus]" plain>
                  {{ ORDER_STATUS_LABELS[item.orderStatus] }}
                </wd-tag>
              </view>
            </view>
            <view class="muted-text">
              {{ item.stationName }}<text v-if="item.deviceNo">
                · {{ item.deviceNo }}
              </text>
            </view>
            <view class="txn-meta">
              <text class="muted-text">
                {{ formatBizTimeShort(item.createTime) }}
              </text>
              <view class="txn-figures">
                <text class="muted-text">
                  {{ item.actualVolumeMl !== undefined ? formatMl(item.actualVolumeMl) : '—' }}
                </text>
                <text class="txn-amount">
                  {{ formatFen(item.orderAmountFen) }}
                </text>
              </view>
            </view>
          </view>
        </template>

        <view class="page-section readonly-footer">
          <wd-icon name="lock-on" size="14px" color="#646a73" />
          <text>机主交易视图只读：不计算提现或分账</text>
        </view>
      </template>
    </template>
  </view>
</template>

<style scoped lang="scss">
.status-actions {
  display: flex;
  justify-content: center;
  margin-top: 16px;
  width: 100%;
}

.filter-note {
  margin-top: 8px;
  padding: 0 4px;
  line-height: 1.6;
}

.summary-card {
  padding: 14px 16px;
  border-radius: 12px;
  background: #fff;
}

.summary-line {
  font-size: 15px;
  font-weight: 600;
}

.summary-note {
  margin-top: 6px;
  line-height: 1.6;
}

.txn-card {
  padding: 14px 16px;
  border-radius: 12px;
  background: #fff;
}

.txn-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.txn-no {
  font-size: 14px;
  font-weight: 600;
}

.txn-tags {
  display: flex;
  gap: 6px;
}

.txn-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.txn-figures {
  display: flex;
  align-items: center;
  gap: 12px;
}

.txn-amount {
  font-size: 15px;
  font-weight: 600;
}

.readonly-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 12px 0;
  color: var(--app-text-secondary);
  font-size: 13px;
}
</style>
