<script setup lang="ts">
import type { StationSummary } from '@/api/catalog'
import type { TagTone } from '@/utils/format'
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { catalogApi } from '@/api/catalog'
import { ContractError } from '@/api/common'
import AppNavbar from '@/components/app-navbar.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import { setDraftStation } from '@/store/delivery-draft'
import { backOr } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '附近水站',
  },
})

const toast = useToast()
const stationNotice = '暂不获取定位，无法显示距离。'

/** 水站营业状态中文口径（utils/format 暂无该映射，页面内先行冻结）。 */
const STATION_STATUS_LABELS: Record<StationSummary['status'], string> = {
  OPEN: '营业中',
  MAINTENANCE: '检修中',
  CLOSED: '已关闭',
}

const STATION_STATUS_TONES: Record<StationSummary['status'], TagTone> = {
  OPEN: 'success',
  MAINTENANCE: 'warning',
  CLOSED: 'default',
}

const pageState = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')

const stations = ref<StationSummary[]>([])
const targetStationId = ref('')
const selectMode = ref(false)

/** stationId 参数存在时目标水站排首位并加“目标水站”标识。 */
const sortedStations = computed(() => {
  if (!targetStationId.value) {
    return stations.value
  }
  const target = stations.value.filter(item => item.id === targetStationId.value)
  const rest = stations.value.filter(item => item.id !== targetStationId.value)
  return [...target, ...rest]
})

function formatDistance(meters?: number) {
  if (meters === undefined) {
    return '未定位'
  }
  return `${(meters / 1000).toFixed(1)}km`
}

onLoad((query?: Record<string, string | undefined>) => {
  targetStationId.value = query?.stationId ?? ''
  selectMode.value = query?.selectMode === 'delivery'
  void load()
})

async function load() {
  pageState.value = 'loading'
  try {
    stations.value = await catalogApi.listStations()
    pageState.value = 'ready'
  }
  catch (error) {
    pageState.value = 'error'
    errorMessage.value = error instanceof ContractError ? error.message : '水站列表加载失败，请重试'
  }
}

/**
 * 选择模式（U07 → U08 合同）：点选水站写入配送草稿并返回下单页；
 * 非营业中的水站原位说明原因，不写入草稿。浏览模式点按无跳转。
 */
function handleStationTap(station: StationSummary) {
  if (!selectMode.value) {
    return
  }
  if (station.status !== 'OPEN') {
    toast.show(`该水站${STATION_STATUS_LABELS[station.status]}，暂不可选`)
    return
  }
  setDraftStation(station.id)
  uni.navigateBack({
    fail: () => backOr('U08'),
  })
}
</script>

<template>
  <view class="page-shell screen-u07">
    <AppNavbar title="附近水站" back-to="U01" />
    <wd-toast />
    <AppPrototypeNotice :text="stationNotice" />

    <view v-if="pageState === 'loading'" class="page-section loading-box">
      <wd-loading />
      <view class="muted-text">
        正在加载水站列表…
      </view>
    </view>

    <view v-else-if="pageState === 'error'" class="page-section">
      <wd-status-tip image="network" :tip="errorMessage">
        <template #bottom>
          <view class="status-actions">
            <wd-button plain @click="load">
              重新加载
            </wd-button>
            <wd-button plain @click="backOr('U01')">
              返回首页
            </wd-button>
          </view>
        </template>
      </wd-status-tip>
    </view>

    <template v-else>
      <view v-if="!sortedStations.length" class="page-section">
        <wd-status-tip image="content" tip="暂无水站数据">
          <template #bottom>
            <view class="status-actions">
              <wd-button plain @click="backOr('U01')">
                返回首页
              </wd-button>
            </view>
          </template>
        </wd-status-tip>
      </view>

      <view v-for="station in sortedStations" :key="station.id" class="page-section">
        <wd-card custom-class="block-card">
          <template #title>
            <view class="card-title-row">
              <view class="station-title">
                <view class="station-name">
                  {{ station.stationName }}
                </view>
                <wd-tag v-if="station.id === targetStationId" type="primary" plain>
                  目标水站
                </wd-tag>
              </view>
              <wd-tag :type="STATION_STATUS_TONES[station.status]" plain>
                {{ STATION_STATUS_LABELS[station.status] }}
              </wd-tag>
            </view>
          </template>
          <view class="station-body" @click="handleStationTap(station)">
            <view class="station-address">
              <wd-icon name="location" size="14px" color="#646a73" />
              <view>{{ station.address }}</view>
            </view>
            <view class="station-metrics">
              <view class="station-metric">
                <view class="station-metric-value">
                  {{ formatDistance(station.distanceMeters) }}
                </view>
                <view class="muted-text">
                  距离
                </view>
              </view>
              <view class="station-metric">
                <view class="station-metric-value">
                  {{ station.onlineDeviceCount }}
                </view>
                <view class="muted-text">
                  在线设备
                </view>
              </view>
              <view class="station-metric">
                <view class="station-metric-value">
                  {{ station.availableOutletCount }}
                </view>
                <view class="muted-text">
                  可用出水口
                </view>
              </view>
            </view>
            <view v-if="selectMode" class="station-select">
              选择该水站 <wd-icon name="arrow-right" size="14px" />
            </view>
          </view>
        </wd-card>
      </view>

      <view v-if="!selectMode && sortedStations.length" class="page-section muted-text browse-note">
        设备明细请扫码查看。
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.loading-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 64px 0;
}

.status-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-top: 20px;
}

.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
}

.station-title {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.station-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.station-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.station-address {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--app-text-secondary);
  font-size: 13px;
}

.station-metrics {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 8px;
}

.station-metric {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.station-metric-value {
  font-size: 17px;
  font-weight: 600;
}

.station-select {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: 6px 0 2px;
  color: var(--wot-color-theme, var(--app-color-primary));
  font-size: 14px;
}

.browse-note {
  text-align: center;
}
</style>
