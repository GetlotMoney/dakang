<script setup lang="ts">
import type { IdentityDashboard } from '@/api/identity'
import type { EntityId } from '@/api/common'
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { identityApi } from '@/api/identity'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import { formatBizTime, formatFen } from '@/utils/format'
import { goTo } from '@/utils/navigation'

definePage({ style: { navigationStyle: 'custom', navigationBarTitleText: '区域运营' } })
const toast = useToast()
const message = useMessage()
const state = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
const data = ref<IdentityDashboard | null>(null)
const confirming = ref<EntityId | null>(null)
const levelLabel: Record<number, string> = { 1: '省级', 2: '市级', 3: '区县级' }
onShow(load)
async function load() {
  state.value = 'loading'
  try {
    data.value = await identityApi.regionOverview()
    state.value = 'ready'
  }
  catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '区域数据加载失败'
    state.value = 'error'
  }
}
function copy(code: string) {
  uni.setClipboardData({ data: code, success: () => toast.success('邀请码已复制') })
}
async function confirmLead(leadId: EntityId) {
  try {
    await message.confirm({ title: '确认公域机主', msg: '确认后建立该机主与当前代理血缘，后续订单按血缘分润。继续？' })
  }
  catch {
    return
  }
  confirming.value = leadId
  try {
    data.value = await identityApi.confirmLead(leadId)
    toast.success('血缘关系已确认')
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '确认失败')
  }
  finally {
    confirming.value = null
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="区域运营" back-to="U01" /><wd-toast /><wd-message-box />
    <view v-if="state === 'loading'" class="page-section">
      <AppPageState state="loading" />
    </view>
    <view v-else-if="state === 'error'" class="page-section">
      <AppPageState state="error" :message="errorMessage" @retry="load" />
    </view>
    <template v-else-if="data">
      <view class="page-section dashboard-hero">
        <view class="dashboard-hero__eyebrow">
          {{ levelLabel[data.agentLevel || 0] }}代理 · {{ data.regionName }}
        </view>
        <view class="dashboard-hero__amount">
          {{ formatFen(data.wallet.balanceFen) }}
        </view><view class="dashboard-hero__label">
          可提现区域分润
        </view>
        <view class="dashboard-hero__actions">
          <wd-button size="small" plain @click="goTo('O06')">
            收益与提现
          </wd-button><wd-button size="small" plain @click="goTo('I01')">
            身份管理
          </wd-button>
        </view>
      </view>
      <view class="page-section metric-grid">
        <view class="surface-card metric">
          <text class="metric__value">
            {{ data.directOwnerCount }}
          </text><text class="readout-label">
            血缘机主
          </text>
        </view><view class="surface-card metric">
          <text class="metric__value">
            {{ data.stationCount }}
          </text><text class="readout-label">
            关联水站
          </text>
        </view><view class="surface-card metric">
          <text class="metric__value">
            {{ data.orderCount }}
          </text><text class="readout-label">
            关联订单
          </text>
        </view>
      </view>
      <view class="page-section">
        <wd-card title="公域机主轮转">
          <view v-if="!data.publicLeads.length" class="muted-text">
            当前没有分配给你的公域机主。
          </view>
          <view v-for="lead in data.publicLeads" :key="lead.leadId" class="lead-row">
            <view>
              <view class="lead-row__name">
                {{ lead.ownerName }} · {{ lead.regionName }}
              </view><view class="muted-text">
                {{ lead.status === 3 ? '已确认血缘' : `请在 ${formatBizTime(lead.responseDeadline)} 前确认` }}
              </view>
            </view>
            <wd-button v-if="lead.status === 2" size="small" :loading="confirming === lead.leadId" @click="confirmLead(lead.leadId)">
              确认
            </wd-button><wd-tag v-else type="success" plain>
              已确认
            </wd-tag>
          </view>
        </wd-card>
      </view>
      <view class="page-section">
        <wd-card title="我的血缘">
          <view v-for="node in data.lineage" :key="node.profileId" class="lineage-row">
            <view class="lineage-dot" /><view>
              <view class="lineage-row__name">
                {{ node.subjectName }}<wd-tag v-if="node.current" type="primary" plain custom-class="current-tag">
                  当前
                </wd-tag>
              </view><view class="muted-text">
                {{ levelLabel[node.level || 0] || '渠道' }} · {{ node.regionName }}
              </view>
            </view>
          </view>
        </wd-card>
      </view>
      <view class="page-section">
        <wd-card title="我的邀请码">
          <view v-for="item in data.inviteCodes" :key="item.code" class="invite-row pressable" @click="copy(item.code)">
            <view>
              <view class="invite-row__code">
                {{ item.code }}
              </view><view class="muted-text">
                用于邀请{{ item.targetCapabilityType === 2 ? '机主' : item.targetCapabilityType === 3 ? '渠道' : '下级代理' }}
              </view>
            </view><wd-icon name="copy" size="18px" />
          </view>
        </wd-card>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.dashboard-hero { padding: var(--sp-5); border-radius: var(--r-lg); color: var(--app-text-inverse); background: var(--app-color-primary-deep); box-shadow: var(--sh-card); }
.dashboard-hero__eyebrow,.dashboard-hero__label { font-size: var(--fs-caption); opacity: .8; }.dashboard-hero__amount { margin: var(--sp-2) 0 var(--sp-1); font-size: var(--fs-display); font-weight: 750; }.dashboard-hero__actions { display:flex; gap:var(--sp-2); margin-top:var(--sp-4); }
.metric-grid { display:grid; grid-template-columns:repeat(3,1fr); gap:var(--sp-2); }.metric{text-align:center}.metric__value{display:block;font-size:var(--fs-metric);font-weight:700}
.lead-row,.invite-row,.lineage-row{display:flex;align-items:center;justify-content:space-between;gap:var(--sp-3);padding:var(--sp-3) 0}.lead-row + .lead-row,.invite-row + .invite-row,.lineage-row + .lineage-row{border-top:1px solid var(--line-1)}.lead-row__name,.invite-row__code,.lineage-row__name{font-size:var(--fs-body);font-weight:650}.lineage-row{justify-content:flex-start}.lineage-dot{width:8px;height:8px;flex:none;border-radius:var(--r-pill);background:var(--app-color-primary)}
:deep(.current-tag){margin-left:var(--sp-2)}
</style>
