<script setup lang="ts">
import type { DeviceSummary, OwnerOverview } from '@/api/device'
import { computed, ref, watch } from 'vue'
import { ContractError } from '@/api/common'
import { deviceApi } from '@/api/device'
import { formatBizTimeShort, formatFen, formatMl } from '@/utils/format'
import { goTo } from '@/utils/navigation'

/** 经营态：U01 的机主视角（主态自适应三张脸之一，只读监控，无任何控制入口）。 */
const props = defineProps<{ refreshTick: number }>()
const emit = defineEmits<{ switchFace: [face: 'life'] }>()

const loading = ref(true)
const blockedReason = ref('')
const overview = ref<OwnerOverview | null>(null)
const attentionDevices = ref<DeviceSummary[]>([])

const attentionTop = computed(() => attentionDevices.value.slice(0, 2))

watch(() => props.refreshTick, refresh, { immediate: true })

async function refresh() {
  loading.value = true
  blockedReason.value = ''
  try {
    const [summary, devices] = await Promise.all([
      deviceApi.getOwnerOverview(),
      deviceApi.listOwnerDevices(),
    ])
    overview.value = summary
    attentionDevices.value = devices.filter(
      item => item.onlineStatus === 'OFFLINE' || item.runStatus === 'FAULT',
    )
  }
  catch (error) {
    overview.value = null
    attentionDevices.value = []
    blockedReason.value = error instanceof ContractError ? error.message : '经营数据暂不可用'
  }
  finally {
    loading.value = false
  }
}
</script>

<template>
  <view>
    <view class="page-section">
      <wd-card custom-class="home-card">
        <template #title>
          <view class="face-title-row">
            <view>经营概览</view>
            <wd-tag type="primary" plain>
              只读监控
            </wd-tag>
          </view>
        </template>
        <template v-if="overview">
          <view class="owner-metrics">
            <view class="owner-metric">
              <view class="owner-metric-value">
                {{ overview.deviceCount }}
              </view>
              <view class="muted-text">
                设备
              </view>
            </view>
            <view class="owner-metric">
              <view class="owner-metric-value">
                {{ overview.onlineCount }}
              </view>
              <view class="muted-text">
                在线
              </view>
            </view>
            <view class="owner-metric" :class="{ 'owner-metric-warn': overview.offlineCount > 0 }">
              <view class="owner-metric-value">
                {{ overview.offlineCount }}
              </view>
              <view class="muted-text">
                离线
              </view>
            </view>
            <view class="owner-metric" :class="{ 'owner-metric-warn': overview.faultCount > 0 }">
              <view class="owner-metric-value">
                {{ overview.faultCount }}
              </view>
              <view class="muted-text">
                故障
              </view>
            </view>
          </view>
          <view class="owner-revenue muted-text">
            周期订单 {{ overview.orderCount }} 单 · 出水 {{ formatMl(overview.actualVolumeMl) }} · 金额 {{ formatFen(overview.orderAmountFen) }}（订单口径，非可提现收益）
          </view>
        </template>
        <view v-else-if="blockedReason" class="face-blocked">
          {{ blockedReason }}
        </view>
        <view v-else class="muted-text">
          加载中…
        </view>
      </wd-card>
    </view>

    <view v-if="attentionTop.length" class="page-section">
      <wd-card title="待关注设备" custom-class="home-card">
        <wd-cell-group>
          <wd-cell
            v-for="item in attentionTop"
            :key="item.deviceNo"
            :title="item.deviceNo"
            :label="`${item.onlineStatus === 'OFFLINE' ? '离线' : '在线'} · 心跳 ${formatBizTimeShort(item.lastHeartbeat)}${item.lastFaultCode ? ` · 故障 ${item.lastFaultCode}` : ''}`"
            is-link
            @click="goTo('O03', { deviceNo: item.deviceNo })"
          />
        </wd-cell-group>
      </wd-card>
    </view>

    <view class="page-section">
      <wd-cell-group border>
        <wd-cell title="设备列表" icon="computer" is-link @click="goTo('O02')" />
        <wd-cell title="交易快照" icon="chart" is-link @click="goTo('O04')" />
        <wd-cell title="报修与配件" icon="tools" is-link @click="goTo('O05')" />
        <wd-cell
          title="生活用水服务"
          label="扫码取水 · 水卡 · 配送订水"
          icon="user"
          is-link
          @click="emit('switchFace', 'life')"
        />
      </wd-cell-group>
      <view class="muted-text owner-footnote">
        机主视角为只读监控：无提现、无远程控制。
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.face-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.face-blocked {
  margin-top: 8px;
  color: var(--wot-color-danger, #fa4350);
  font-size: 13px;
}

.owner-metrics {
  display: flex;
  align-items: center;
  justify-content: space-around;
  padding: 4px 0 8px;
}

.owner-metric {
  text-align: center;
}

.owner-metric-value {
  font-size: 22px;
  font-weight: 600;
}

.owner-metric-warn .owner-metric-value {
  color: var(--wot-color-danger, #fa4350);
}

.owner-revenue {
  line-height: 1.6;
}

.owner-footnote {
  margin-top: 8px;
  padding: 0 4px;
}
</style>
