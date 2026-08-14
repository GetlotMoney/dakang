<script setup lang="ts">
import type { DeviceSummary, OwnerOverview } from '@/api/device'
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { ContractError } from '@/api/common'
import { deviceApi } from '@/api/device'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import {
  ONLINE_STATUS_LABELS,
  ONLINE_STATUS_TONES,
  RUN_STATUS_LABELS,
  RUN_STATUS_TONES,
} from '@/pages/owner/owner-labels'
import { formatBizTimeShort, formatFen, formatMl } from '@/utils/format'
import { backOr, goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '经营概览',
  },
})

/** 金额口径单列：毛额、退款不冲减、非可提现——三件事都必须让机主看见，避免把订单额当收入。 */
const AMOUNT_NOTE
  = '金额为订单毛额（含已退款），分润净额见「收益钱包」'

const loading = ref(true)
const errorMessage = ref('')
const overview = ref<OwnerOverview | null>(null)
const attentionDevices = ref<DeviceSummary[]>([])

let fetching = false

// onShow 刷新：从 O02/O04/O05 返回后重新聚合，避免展示过期快照。
onShow(refresh)

async function refresh() {
  if (fetching) {
    return
  }
  fetching = true
  loading.value = true
  errorMessage.value = ''
  try {
    const [data, devices] = await Promise.all([
      deviceApi.getOwnerOverview(),
      deviceApi.listOwnerDevices(),
    ])
    overview.value = data
    attentionDevices.value = devices.filter(
      item => item.onlineStatus === 'OFFLINE' || item.runStatus === 'FAULT',
    )
  }
  catch (error) {
    // OWNER_SCOPE_DENIED / CAPABILITY_DENIED 等契约拒绝直接展示原因，不伪装空数据。
    overview.value = null
    attentionDevices.value = []
    errorMessage.value = error instanceof ContractError ? error.message : '经营数据加载失败，请稍后重试'
  }
  finally {
    fetching = false
    loading.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="经营概览" back-to="U01" />

    <view v-if="loading" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, { width: '60%' }]" />
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain size="small" @click="backOr('U01')">
            返回
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else-if="overview">
      <view class="owner-hero">
        <image
          class="owner-hero__art"
          src="/static/brand/owner-network.jpg"
          mode="aspectFill"
        />
        <view class="owner-hero__copy">
          <text class="owner-hero__title">
            水站运营网络
          </text>
          <view class="owner-hero__scope">
            <wd-icon name="dashboard" size="15px" color="var(--app-color-primary)" />
            <text>授权 {{ overview.stationCount }} 个水站 · {{ overview.deviceCount }} 台设备</text>
          </view>
        </view>
      </view>

      <view class="page-section metric-row">
        <view class="metric-card">
          <view class="metric-value metric-value--online">
            {{ overview.onlineCount }}
          </view>
          <view class="muted-text">
            在线
          </view>
        </view>
        <view class="metric-card">
          <view class="metric-value" :class="{ 'metric-value--warning': overview.offlineCount > 0 }">
            {{ overview.offlineCount }}
          </view>
          <view class="muted-text">
            离线
          </view>
        </view>
        <view class="metric-card">
          <view class="metric-value" :class="{ 'metric-value--danger': overview.faultCount > 0 }">
            {{ overview.faultCount }}
          </view>
          <view class="muted-text">
            故障
          </view>
        </view>
      </view>

      <view class="page-section">
        <wd-card title="周期经营">
          <view class="snapshot-line">
            订单 {{ overview.orderCount }} 单 · 出水 {{ formatMl(overview.actualVolumeMl) }} · 订单金额 {{ formatFen(overview.orderAmountFen) }}
          </view>
          <view class="muted-text">
            统计周期 {{ formatBizTimeShort(overview.periodStart) }} ~ {{ formatBizTimeShort(overview.periodEnd) }}
          </view>
          <view class="muted-text caliber-note">
            {{ AMOUNT_NOTE }}
          </view>
          <view v-if="overview.prepaidVolumeMl > 0" class="muted-text caliber-note">
            其中 {{ formatMl(overview.prepaidVolumeMl) }} 为水卡水量，不计入本期金额
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card title="待关注设备">
          <template v-if="attentionDevices.length">
            <view
              v-for="item in attentionDevices"
              :key="item.deviceNo"
              class="attention-row"
              @click="goTo('O03', { deviceNo: item.deviceNo })"
            >
              <view class="attention-main">
                <view class="attention-device">
                  {{ item.deviceNo }}
                </view>
                <view class="muted-text">
                  最后心跳 {{ formatBizTimeShort(item.lastHeartbeat) }}
                  <text v-if="item.lastFaultCode" class="fault-code-text">
                    · 故障码 {{ item.lastFaultCode }}
                  </text>
                </view>
              </view>
              <view class="attention-side">
                <wd-tag :type="ONLINE_STATUS_TONES[item.onlineStatus]" plain>
                  {{ ONLINE_STATUS_LABELS[item.onlineStatus] }}
                </wd-tag>
                <wd-tag v-if="item.runStatus === 'FAULT'" :type="RUN_STATUS_TONES[item.runStatus]" plain>
                  {{ RUN_STATUS_LABELS[item.runStatus] }}
                </wd-tag>
                <wd-icon name="arrow-right" size="14px" color="var(--app-text-secondary)" />
              </view>
            </view>
          </template>
          <view v-else class="muted-text">
            暂无离线或故障设备
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-cell-group border>
          <wd-cell title="设备列表" icon="computer" is-link @click="goTo('O02')" />
          <wd-cell title="交易快照" icon="chart" is-link @click="goTo('O04')" />
          <wd-cell title="收益钱包" icon="money-circle" is-link @click="goTo('O06')" />
          <wd-cell title="报修与配件" icon="tools" is-link @click="goTo('O05')" />
        </wd-cell-group>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.owner-hero {
  position: relative;
  height: 170px;
  margin-top: var(--gap-hero);
  overflow: hidden;
  border-radius: var(--r-md);
  background: var(--app-bg-card);

  &__art {
    width: 100%;
    height: 100%;
  }

  &__copy {
    position: absolute;
    top: var(--sp-4);
    left: var(--sp-4);
    display: flex;
    max-width: 45%;
    flex-direction: column;
  }

  &__title {
    color: var(--app-text-primary);
    font-size: var(--fs-title);
    font-weight: 700;
  }

  &__scope {
    display: flex;
    gap: var(--sp-1);
    align-items: flex-start;
    margin-top: var(--sp-2);
    color: var(--app-text-secondary);
    font-size: var(--fs-note);
    line-height: 1.45;
  }
}

.metric-row {
  display: flex;
  gap: 8px;
}

.metric-card {
  flex: 1;
  padding: 14px 0;
  border-radius: 12px;
  background: var(--app-bg-card);
  text-align: center;
}

.metric-value {
  font-size: 22px;
  font-weight: 600;
}

.metric-value--online {
  color: var(--app-color-success);
}

.metric-value--warning {
  color: var(--app-color-warning-text);
}

.metric-value--danger {
  color: var(--app-color-danger);
}

.snapshot-line {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 6px;
}

.caliber-note {
  margin-top: 8px;
  line-height: 1.6;
}

.attention-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;

  & + & {
    border-top: 1px solid var(--line-1);
  }
}

.attention-device {
  font-size: 15px;
  font-weight: 600;
}

.attention-side {
  display: flex;
  align-items: center;
  gap: 6px;
}

.fault-code-text {
  color: var(--app-color-danger);
}
</style>
