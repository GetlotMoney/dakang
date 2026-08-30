<script setup lang="ts">
import type { IdentityDashboard } from '@/api/identity'
import { computed, ref, watch } from 'vue'
import { identityApi } from '@/api/identity'
import AppPageState from '@/components/app-page-state.vue'
import { formatFen } from '@/utils/format'
import { goTo } from '@/utils/navigation'

const props = defineProps<{ refreshTick: number }>()
const data = ref<IdentityDashboard | null>(null)
const error = ref('')
const levels: Record<number, string> = { 1: '省级', 2: '市级', 3: '区县级' }
const regionIncome = computed(() => data.value?.wallet.roleSummaries.find(item => item.receiverType === 6)?.settledFen ?? 0)

watch(() => props.refreshTick, load, { immediate: true })
async function load() {
  try {
    data.value = await identityApi.regionOverview()
    error.value = ''
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : '区域数据加载失败'
  }
}
</script>

<template>
  <view class="face-shell">
    <AppPageState v-if="error" state="error" :message="error" @retry="load" /><AppPageState v-else-if="!data" state="loading" /><template v-else>
      <view class="face-hero">
        <view class="face-hero__eyebrow">
          {{ data.regionName }} · {{ levels[data.agentLevel || 0] }}代理
        </view><view class="face-hero__amount">
          {{ formatFen(regionIncome) }}
        </view><view class="face-hero__label">
          累计区域分润
        </view>
      </view><view class="face-metrics">
        <view>
          <view class="face-metric-value">
            {{ data.directOwnerCount }}
          </view><view class="face-metric-label">
            血缘机主
          </view>
        </view><view>
          <view class="face-metric-value">
            {{ data.stationCount }}
          </view><view class="face-metric-label">
            关联水站
          </view>
        </view><view>
          <view class="face-metric-value">
            {{ data.publicLeads.filter(item => item.status === 2).length }}
          </view><view class="face-metric-label">
            待确认线索
          </view>
        </view>
      </view><view class="face-actions">
        <wd-button block @click="goTo('R01')">
          进入区域工作台
        </wd-button><wd-button block plain @click="goTo('O06')">
          查看收益与提现
        </wd-button>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.face-shell{margin-top:var(--gap-hero)}.face-hero{padding:var(--sp-5);border-radius:var(--r-lg);color:var(--app-text-inverse);background:var(--app-color-primary-deep);box-shadow:var(--sh-card)}.face-hero__eyebrow,.face-hero__label{font-size:var(--fs-caption);opacity:.8}.face-hero__amount{margin:var(--sp-2) 0;font-size:var(--fs-display);font-weight:750}.face-metrics{display:grid;grid-template-columns:repeat(3,1fr);margin-top:var(--sp-3);padding:var(--sp-4);border-radius:var(--r-md);background:var(--app-bg-card);box-shadow:var(--sh-card);text-align:center}.face-metric-value{font-size:var(--fs-metric)}.face-metric-label{margin-top:var(--sp-1);color:var(--app-text-tertiary);font-size:var(--fs-caption)}.face-actions{display:grid;gap:var(--sp-3);margin-top:var(--sp-4)}
</style>
