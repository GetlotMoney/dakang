<script setup lang="ts">
import type { IdentityDashboard } from '@/api/identity'
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { identityApi } from '@/api/identity'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import { formatFen } from '@/utils/format'
import { goTo } from '@/utils/navigation'

definePage({ style: { navigationStyle: 'custom', navigationBarTitleText: '渠道推广' } })
const toast = useToast()
const state = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
const data = ref<IdentityDashboard | null>(null)
onShow(load)
async function load() {
  state.value = 'loading'
  try {
    data.value = await identityApi.channelOverview()
    state.value = 'ready'
  }
  catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '渠道数据加载失败'
    state.value = 'error'
  }
}
function copy(code: string) {
  uni.setClipboardData({ data: code, success: () => toast.success('邀请码已复制') })
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="渠道推广" back-to="U01" />
    <wd-toast />
    <view v-if="state === 'loading'" class="page-section">
      <AppPageState state="loading" />
    </view>
    <view v-else-if="state === 'error'" class="page-section">
      <AppPageState state="error" :message="errorMessage" @retry="load" />
    </view>
    <template v-else-if="data">
      <view class="page-section dashboard-hero">
        <view class="dashboard-hero__eyebrow">
          {{ data.regionName }} · {{ data.subjectName }}
        </view>
        <view class="dashboard-hero__amount">
          {{ formatFen(data.wallet.balanceFen) }}
        </view>
        <view class="dashboard-hero__label">
          可提现推广收益
        </view>
        <view class="dashboard-hero__actions">
          <wd-button size="small" plain @click="goTo('O06')">
            收益与提现
          </wd-button>
          <wd-button size="small" plain @click="goTo('I01')">
            身份管理
          </wd-button>
        </view>
      </view>
      <view class="page-section metric-grid">
        <view class="surface-card metric">
          <text class="metric__value">
            {{ data.directOwnerCount }}
          </text><text class="readout-label">
            直属机主
          </text>
        </view>
        <view class="surface-card metric">
          <text class="metric__value">
            {{ data.stationCount }}
          </text><text class="readout-label">
            关联水站
          </text>
        </view>
        <view class="surface-card metric">
          <text class="metric__value">
            {{ data.orderCount }}
          </text><text class="readout-label">
            关联订单
          </text>
        </view>
      </view>
      <view class="page-section">
        <wd-card title="我的推广码">
          <view v-if="!data.inviteCodes.length" class="muted-text">
            暂无有效邀请码
          </view>
          <view v-for="item in data.inviteCodes" :key="item.code" class="invite-row pressable" @click="copy(item.code)">
            <view>
              <view class="invite-row__code">
                {{ item.code }}
              </view><view class="muted-text">
                {{ item.regionName }} · 已使用 {{ item.usedCount }} 次
              </view>
            </view>
            <wd-icon name="copy" size="18px" />
          </view>
        </wd-card>
      </view>
      <view class="page-section">
        <wd-card title="我发展的机主">
          <view v-if="!data.owners.length" class="muted-text">
            暂未发展机主，复制推广码邀请机主申请。
          </view>
          <view v-for="owner in data.owners" :key="owner.userId" class="owner-row">
            <view>
              <view class="owner-row__name">
                {{ owner.userName }}
              </view><view class="muted-text">
                用户 {{ owner.userId }}
              </view>
            </view>
            <view class="owner-row__side">
              {{ owner.stationCount }} 个水站
            </view>
          </view>
        </wd-card>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.dashboard-hero { padding: var(--sp-5); border-radius: var(--r-lg); color: var(--app-text-inverse); background: var(--app-color-primary-deep); box-shadow: var(--sh-card); }
.dashboard-hero__eyebrow,.dashboard-hero__label { font-size: var(--fs-caption); opacity: .8; }
.dashboard-hero__amount { margin: var(--sp-2) 0 var(--sp-1); font-size: var(--fs-display); font-weight: 750; }
.dashboard-hero__actions { display: flex; gap: var(--sp-2); margin-top: var(--sp-4); }
.metric-grid { display: grid; grid-template-columns: repeat(3,1fr); gap: var(--sp-2); }
.metric { text-align: center; }
.metric__value { display: block; font-size: var(--fs-metric); font-weight: 700; }
.invite-row,.owner-row { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); padding: var(--sp-3) 0; }
.invite-row + .invite-row,.owner-row + .owner-row { border-top: 1px solid var(--line-1); }
.invite-row__code,.owner-row__name { font-size: var(--fs-body); font-weight: 650; }
.owner-row__side { color: var(--app-color-primary); font-size: var(--fs-caption); }
</style>
