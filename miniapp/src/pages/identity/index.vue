<script setup lang="ts">
import type { IdentityRole } from '@/api/identity'
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { identityApi } from '@/api/identity'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import { formatBizTime } from '@/utils/format'
import { goTo } from '@/utils/navigation'

definePage({ style: { navigationStyle: 'custom', navigationBarTitleText: '身份与能力' } })

const toast = useToast()
const state = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
const roles = ref<IdentityRole[]>([])
const demoMode = ref(false)

const iconByType: Record<number, string> = { 1: 'goods', 2: 'computer', 3: 'share', 4: 'location' }
const applyTypeByRole: Record<number, 'owner' | 'channel' | 'region'> = { 2: 'owner', 3: 'channel', 4: 'region' }

onShow(load)

async function load() {
  state.value = 'loading'
  try {
    const data = await identityApi.overview()
    roles.value = data.roles
    demoMode.value = data.demoMode
    state.value = 'ready'
  }
  catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '身份状态加载失败'
    state.value = 'error'
  }
}

function tagType(status: number) {
  if (status === 2)
    return 'success'
  if (status === 1)
    return 'warning'
  if (status === 3 || status === 4)
    return 'danger'
  return 'primary'
}

function handleRole(role: IdentityRole) {
  if (role.capabilityType === 1) {
    goTo('D02')
    return
  }
  if (role.status === 2) {
    if (role.capabilityType === 2)
      goTo('O01')
    if (role.capabilityType === 3)
      goTo('H01')
    if (role.capabilityType === 4)
      goTo('R01')
    return
  }
  if (role.status === 1) {
    toast.info('申请正在审核中')
    return
  }
  if (role.status === 3) {
    toast.warning('该身份已暂停，请联系运营处理')
    return
  }
  const type = applyTypeByRole[role.capabilityType]
  if (type)
    goTo('I02', { type })
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="身份与能力" back-to="U03" />
    <wd-toast />
    <view v-if="state === 'loading'" class="page-section">
      <AppPageState state="loading" />
    </view>
    <view v-else-if="state === 'error'" class="page-section">
      <AppPageState state="error" :message="errorMessage" @retry="load" />
    </view>
    <template v-else>
      <view class="page-section identity-intro surface-card">
        <view class="identity-intro__title">
          默认只有普通用户身份
        </view>
        <view class="muted-text">
          配送员、机主、渠道和区域代理分别申请；审核通过后才出现在首页工作台。
        </view>
      </view>
      <view class="page-section role-list">
        <view v-for="role in roles" :key="role.capabilityType" class="role-card surface-card pressable" @click="handleRole(role)">
          <view class="role-card__icon">
            <wd-icon :name="iconByType[role.capabilityType]" size="22px" />
          </view>
          <view class="role-card__content">
            <view class="role-card__head">
              <text class="role-card__title">
                {{ role.title }}
              </text>
              <wd-tag :type="tagType(role.status)" plain>
                {{ role.statusText }}
              </wd-tag>
            </view>
            <view v-if="role.applicantName" class="muted-text">
              {{ role.applicantName }}<text v-if="role.regionName">
                · {{ role.regionName }}
              </text>
            </view>
            <view v-else class="muted-text">
              点击查看申请条件
            </view>
            <view v-if="role.submittedTime" class="role-card__time">
              提交于 {{ formatBizTime(role.submittedTime) }}
            </view>
          </view>
          <wd-icon name="arrow-right" size="18px" color="var(--app-text-tertiary)" />
        </view>
      </view>
      <view v-if="demoMode" class="page-section">
        <wd-cell-group border>
          <wd-cell title="演示控制台" label="固定二维码、支付与设备下一次结果" icon="setting" is-link @click="goTo('X01')" />
        </wd-cell-group>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.identity-intro__title { margin-bottom: var(--sp-2); font-size: var(--fs-title); font-weight: 700; }
.role-list { display: grid; gap: var(--sp-3); }
.role-card { display: flex; align-items: center; gap: var(--sp-3); }
.role-card__icon { display: flex; width: 44px; height: 44px; flex: none; align-items: center; justify-content: center; border-radius: var(--r-md); background: var(--tint-primary); color: var(--app-color-primary); }
.role-card__content { min-width: 0; flex: 1; }
.role-card__head { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--sp-1); }
.role-card__title { font-size: var(--fs-body); font-weight: 650; }
.role-card__time { margin-top: var(--sp-1); color: var(--app-text-tertiary); font-size: var(--fs-note); }
</style>
