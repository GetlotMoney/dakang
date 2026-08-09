<script setup lang="ts">
import type { DeviceSummary } from '@/api/device'
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { ContractError } from '@/api/common'
import { deviceApi } from '@/api/device'
import AppNavbar from '@/components/app-navbar.vue'
import {
  ONLINE_STATUS_LABELS,
  ONLINE_STATUS_TONES,
  RUN_STATUS_LABELS,
  RUN_STATUS_TONES,
} from '@/pages/owner/owner-labels'
import { formatBizTimeShort } from '@/utils/format'
import { backOr, goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '机主设备',
  },
})

/**
 * 单列状态筛选：值与路由合同 O02 status 枚举逐字一致，
 * 在线/离线映射 onlineStatus，其余映射 runStatus。
 */
const STATUS_FILTER_COLUMNS = [
  { label: '全部状态', value: '' },
  { label: '在线', value: 'ONLINE' },
  { label: '离线', value: 'OFFLINE' },
  { label: '空闲', value: 'IDLE' },
  { label: '出水中', value: 'DISPENSING' },
  { label: '故障', value: 'FAULT' },
  { label: '维护', value: 'MAINTENANCE' },
  { label: '锁定', value: 'LOCKED' },
]

const loading = ref(true)
const errorMessage = ref('')
const devices = ref<DeviceSummary[]>([])
const statusFilter = ref('')
const stationIdFilter = ref('')

const stationScopeName = computed(() => {
  if (!stationIdFilter.value) {
    return ''
  }
  const matched = devices.value.find(item => item.stationId === stationIdFilter.value)
  return matched ? matched.stationName : `水站 ${stationIdFilter.value}`
})

// 筛选只在已授权设备列表内收窄，不能扩大数据范围（路由合同 O02）。
const filteredDevices = computed(() =>
  devices.value
    .filter(item => !stationIdFilter.value || item.stationId === stationIdFilter.value)
    .filter(item => matchStatus(item, statusFilter.value)),
)

onLoad(async (options) => {
  const status = String(options?.status ?? '')
  if (STATUS_FILTER_COLUMNS.some(item => item.value === status)) {
    statusFilter.value = status
  }
  stationIdFilter.value = String(options?.stationId ?? '')
  await refresh()
})

function matchStatus(device: DeviceSummary, status: string) {
  if (!status) {
    return true
  }
  if (status === 'ONLINE' || status === 'OFFLINE') {
    return device.onlineStatus === status
  }
  return device.runStatus === status
}

async function refresh() {
  loading.value = true
  errorMessage.value = ''
  try {
    devices.value = await deviceApi.listOwnerDevices()
  }
  catch (error) {
    devices.value = []
    errorMessage.value = error instanceof ContractError ? error.message : '设备列表加载失败，请稍后重试'
  }
  finally {
    loading.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="机主设备" back-to="O01" />

    <view v-if="loading" class="page-section muted-text">
      加载中…
    </view>

    <view v-else-if="errorMessage" class="page-section">
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
            v-model="statusFilter"
            label="设备状态"
            :columns="STATUS_FILTER_COLUMNS"
          />
        </wd-cell-group>
        <view v-if="stationScopeName" class="muted-text filter-note">
          已限定水站：{{ stationScopeName }}
        </view>
      </view>

      <view v-if="!devices.length" class="page-section">
        <wd-status-tip image="content" tip="暂无设备" />
      </view>

      <view v-else-if="!filteredDevices.length" class="page-section">
        <wd-status-tip image="search" tip="当前筛选条件下暂无设备" />
      </view>

      <template v-else>
        <view
          v-for="item in filteredDevices"
          :key="item.deviceNo"
          class="page-section device-card"
          @click="goTo('O03', { deviceNo: item.deviceNo })"
        >
          <view class="device-card-head">
            <view>
              <view class="device-no">
                {{ item.deviceNo }}
              </view>
              <view class="muted-text">
                {{ item.deviceName }} · {{ item.stationName }}
              </view>
            </view>
            <wd-icon name="arrow-right" size="16px" color="var(--app-text-secondary)" />
          </view>
          <view class="device-card-tags">
            <wd-tag :type="ONLINE_STATUS_TONES[item.onlineStatus]" plain>
              {{ ONLINE_STATUS_LABELS[item.onlineStatus] }}
            </wd-tag>
            <wd-tag :type="RUN_STATUS_TONES[item.runStatus]" plain>
              {{ RUN_STATUS_LABELS[item.runStatus] }}
            </wd-tag>
          </view>
          <view class="device-card-meta muted-text">
            <text>最后心跳 {{ formatBizTimeShort(item.lastHeartbeat) }}</text>
            <text v-if="item.lastFaultCode" class="fault-code-text">
              故障码 {{ item.lastFaultCode }}
            </text>
          </view>
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

.device-card {
  padding: 14px 16px;
  border-radius: 12px;
  background: #fff;
}

.device-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.device-no {
  font-size: 15px;
  font-weight: 600;
}

.device-card-tags {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.device-card-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.fault-code-text {
  color: var(--app-color-danger);
}
</style>
