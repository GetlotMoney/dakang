<script setup lang="ts">
import type { DeviceDetail } from '@/api/device'
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
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
import { formatBizTime, formatFen } from '@/utils/format'
import { backOr, goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '设备详情',
  },
})

/** 遥测字段降级口径（S09.3）：undefined 一律展示该占位，不猜测数值。 */
const UNSUPPORTED = '未上报/不支持'
const SIM_STATUS_LABELS: Record<number, string> = {
  1: '正常',
  2: '未激活',
  3: '欠费',
  4: '停用',
}

const loading = ref(true)
const errorMessage = ref('')
const deviceNo = ref('')
const detail = ref<DeviceDetail | null>(null)

const telemetryRows = computed(() => {
  const data = detail.value
  if (!data) {
    return []
  }
  return [
    { label: 'TDS', value: data.tds !== undefined ? `${data.tds} ppm` : UNSUPPORTED },
    { label: '水温', value: data.temperatureCelsius !== undefined ? `${data.temperatureCelsius} ℃` : UNSUPPORTED },
    { label: '滤芯剩余', value: data.filterPercent !== undefined ? `${data.filterPercent}%` : UNSUPPORTED },
    { label: '信号强度', value: data.signalDbm !== undefined ? `${data.signalDbm} dBm` : UNSUPPORTED },
  ]
})

onLoad(async (options) => {
  deviceNo.value = String(options?.deviceNo ?? '')
  if (!deviceNo.value) {
    loading.value = false
    errorMessage.value = '未指定设备，无法查看详情'
    return
  }
  await refresh()
})

async function refresh() {
  loading.value = true
  errorMessage.value = ''
  try {
    // 授权范围由契约层重新校验：越权抛 DEVICE_ACCESS_DENIED，不存在抛 DEVICE_NOT_FOUND。
    detail.value = await deviceApi.getOwnerDeviceDetail(deviceNo.value)
  }
  catch (error) {
    detail.value = null
    errorMessage.value = error instanceof ContractError ? error.message : '设备详情加载失败，请稍后重试'
  }
  finally {
    loading.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="设备详情" back-to="O02" />

    <view v-if="loading" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, { width: '60%' }]" />
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain size="small" @click="backOr('O02')">
            返回
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else-if="detail">
      <view class="page-section detail-header">
        <view class="detail-title-row">
          <view>
            <view class="detail-device-no">
              {{ detail.deviceNo }}
            </view>
            <view class="muted-text">
              {{ detail.deviceName }} · {{ detail.stationName }}
            </view>
          </view>
          <view class="detail-tags">
            <wd-tag :type="ONLINE_STATUS_TONES[detail.onlineStatus]" plain>
              {{ ONLINE_STATUS_LABELS[detail.onlineStatus] }}
            </wd-tag>
            <wd-tag :type="RUN_STATUS_TONES[detail.runStatus]" plain>
              {{ RUN_STATUS_LABELS[detail.runStatus] }}
            </wd-tag>
          </view>
        </view>
        <view class="detail-time-row muted-text">
          最后心跳 {{ formatBizTime(detail.lastHeartbeat) }}
        </view>
        <view class="detail-time-row muted-text">
          遥测上报时间 {{ detail.reportTime ? formatBizTime(detail.reportTime) : UNSUPPORTED }}
        </view>
      </view>

      <view class="page-section readonly-banner">
        <wd-icon name="lock-on" size="14px" color="var(--app-text-secondary)" />
        <text>仅查看，不支持远程控制设备</text>
      </view>

      <view class="page-section">
        <wd-cell-group title="遥测数据" border>
          <wd-cell
            v-for="row in telemetryRows"
            :key="row.label"
            :title="row.label"
            :value="row.value"
          />
          <wd-cell title="最近故障码">
            <text :class="detail.lastFaultCode ? 'fault-code-text' : ''">
              {{ detail.lastFaultCode ?? '无' }}
            </text>
          </wd-cell>
        </wd-cell-group>
      </view>

      <view class="page-section">
        <wd-cell-group title="联网信息" border>
          <wd-cell title="SIM 状态" :value="detail.simStatus ? (SIM_STATUS_LABELS[detail.simStatus] ?? '状态异常') : '未配置'" />
          <wd-cell
            title="SIM 到期"
            :value="detail.simExpireTime ? formatBizTime(detail.simExpireTime) : '未配置'"
          />
        </wd-cell-group>
      </view>

      <view class="page-section">
        <wd-card title="出水口">
          <template v-if="detail.outlets.length">
            <view
              v-for="outlet in detail.outlets"
              :key="outlet.outletId"
              class="outlet-row"
            >
              <view>
                <view class="outlet-title">
                  {{ outlet.outletNo }} 号出水口 · {{ outlet.waterTypeName }}
                </view>
                <view class="muted-text">
                  单价 {{ formatFen(outlet.unitPriceFenPerLiter) }}/升
                </view>
              </view>
              <wd-tag :type="outlet.available ? 'success' : 'warning'" plain>
                {{ outlet.available ? '可用' : '不可用' }}
              </wd-tag>
            </view>
          </template>
          <view v-else class="muted-text">
            暂无出水口数据
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-cell-group border>
          <wd-cell
            title="报修此设备"
            icon="service"
            is-link
            @click="goTo('O05', { deviceNo: detail.deviceNo })"
          />
        </wd-cell-group>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.detail-header {
  padding: 16px;
  border-radius: 12px;
  background: var(--app-bg-card);
}

.detail-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.detail-device-no {
  font-size: 17px;
  font-weight: 600;
}

.detail-tags {
  display: flex;
  gap: 6px;
}

.detail-time-row {
  margin-top: 8px;
}

.readonly-banner {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--tint-neutral);
  color: var(--app-text-secondary);
  font-size: 13px;
}

.fault-code-text {
  color: var(--app-color-danger);
}

.outlet-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;

  & + & {
    border-top: 1px solid var(--line-1);
  }
}

.outlet-title {
  font-size: 14px;
  font-weight: 600;
}
</style>
