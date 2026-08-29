<script setup lang="ts">
import type { IdentityApplyInput } from '@/api/identity'
import { onLoad } from '@dcloudio/uni-app'
import { computed, reactive, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { createIdentityRequestId, identityApi } from '@/api/identity'
import AppNavbar from '@/components/app-navbar.vue'
import { useAccountStore } from '@/store/account'
import { goTo } from '@/utils/navigation'

definePage({ style: { navigationStyle: 'custom', navigationBarTitleText: '身份申请' } })

type ApplyType = 'owner' | 'channel' | 'region'
const accountStore = useAccountStore()
const toast = useToast()
const message = useMessage()
const form = ref()
const submitting = ref(false)
const applyType = ref<ApplyType>('owner')
const model = reactive({
  subjectType: 1 as 1 | 2,
  applicantName: '',
  regionName: '武汉市',
  agentLevel: 2 as 1 | 2 | 3,
  inviteCode: '',
  stationName: '',
  stationAddress: '',
  declarationAccepted: false,
})

const capabilityType = computed<2 | 3 | 4>(() => applyType.value === 'owner' ? 2 : applyType.value === 'channel' ? 3 : 4)
const title = computed(() => applyType.value === 'owner' ? '申请成为机主' : applyType.value === 'channel' ? '申请渠道推广' : '申请区域代理')
const inviteRequired = computed(() => applyType.value !== 'owner')

onLoad((options) => {
  const type = options?.type
  if (type === 'owner' || type === 'channel' || type === 'region')
    applyType.value = type
  if (applyType.value === 'channel')
    model.inviteCode = 'DK-DEMO-CHANNEL'
  if (applyType.value === 'region')
    model.inviteCode = 'DK-DEMO-REGION-C'
  model.applicantName = accountStore.context?.userName ?? ''
})

function chooseAgentLevel(level: 1 | 2 | 3) {
  model.agentLevel = level
  model.inviteCode = level === 1 ? 'DK-DEMO-REGION-P' : level === 2 ? 'DK-DEMO-REGION-C' : 'DK-DEMO-REGION-D'
}

async function submit() {
  if (submitting.value)
    return
  const { valid } = await form.value.validate()
  if (!valid)
    return
  if (!model.declarationAccepted) {
    toast.error('请先同意身份合作与业务规范')
    return
  }
  try {
    await message.confirm({ title: title.value, msg: '提交后将按当前环境的审核规则处理，确认提交？' })
  }
  catch { return }
  const input: IdentityApplyInput = {
    capabilityType: capabilityType.value,
    subjectType: model.subjectType,
    applicantName: model.applicantName.trim(),
    regionName: model.regionName.trim(),
    requestId: createIdentityRequestId(),
    declarationAccepted: true,
    ...(applyType.value === 'region' ? { agentLevel: model.agentLevel } : {}),
    ...(model.inviteCode.trim() ? { inviteCode: model.inviteCode.trim() } : {}),
    ...(applyType.value === 'owner' ? { stationName: model.stationName.trim(), stationAddress: model.stationAddress.trim() } : {}),
  }
  submitting.value = true
  try {
    const result = await identityApi.apply(input)
    await accountStore.restoreSession().catch(() => null)
    const role = result.roles.find(item => item.capabilityType === capabilityType.value)
    toast.success(role?.status === 2 ? '申请已自动通过' : '申请已提交')
    setTimeout(() => goTo('I01'), 500)
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '申请提交失败')
  }
  finally { submitting.value = false }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar :title="title" back-to="I01" />
    <wd-toast /><wd-message-box />
    <view class="page-section surface-card apply-note">
      <text>注册后只有普通用户身份。满足申请条件后，演示环境自动通过，正式环境等待后台审核。</text>
    </view>
    <view class="page-section">
      <wd-form ref="form" :model="model">
        <wd-cell-group title="主体信息" border>
          <wd-cell title="主体类型" vertical required>
            <view class="choice-row">
              <view class="choice-pill" :class="{ active: model.subjectType === 1 }" @click="model.subjectType = 1">
                个人
              </view>
              <view class="choice-pill" :class="{ active: model.subjectType === 2 }" @click="model.subjectType = 2">
                企业
              </view>
            </view>
          </wd-cell>
          <wd-input v-model="model.applicantName" label="主体名称" prop="applicantName" required clearable :maxlength="100" :rules="[{ required: true, message: '请填写申请人或企业名称' }]" />
          <wd-input v-model="model.regionName" label="经营区域" prop="regionName" required clearable :maxlength="50" :rules="[{ required: true, message: '请填写唯一经营区域' }]" />
          <wd-cell v-if="applyType === 'region'" title="代理级别" vertical required>
            <view class="choice-row">
              <view v-for="item in [{ v: 1, l: '省级' }, { v: 2, l: '市级' }, { v: 3, l: '区县级' }]" :key="item.v" class="choice-pill" :class="{ active: model.agentLevel === item.v }" @click="chooseAgentLevel(item.v as 1 | 2 | 3)">
                {{ item.l }}
              </view>
            </view>
          </wd-cell>
          <wd-input v-if="applyType !== 'owner' || model.inviteCode" v-model="model.inviteCode" label="邀请码" prop="inviteCode" :required="inviteRequired" clearable :maxlength="50" :rules="inviteRequired ? [{ required: true, message: '请填写有效邀请码' }] : []" />
        </wd-cell-group>
        <wd-cell-group v-if="applyType === 'owner'" title="首个经营水站" border>
          <wd-input v-model="model.stationName" label="水站名称" prop="stationName" required clearable :maxlength="50" :rules="[{ required: true, message: '请填写水站名称' }]" />
          <wd-input v-model="model.stationAddress" label="详细地址" prop="stationAddress" required clearable :maxlength="200" :rules="[{ required: true, message: '请填写水站地址' }]" />
          <wd-input v-model="model.inviteCode" label="推荐码" clearable :maxlength="50" placeholder="选填；不填进入本区域公域池" />
        </wd-cell-group>
        <view class="agreement-row">
          <wd-checkbox v-model="model.declarationAccepted">
            我确认资料真实，并同意身份合作、客户归属和分润规则
          </wd-checkbox>
        </view>
        <view class="submit-row">
          <wd-button block size="large" :loading="submitting" @click="submit">
            提交申请
          </wd-button>
        </view>
      </wd-form>
    </view>
  </view>
</template>

<style scoped lang="scss">
.apply-note { color: var(--app-color-warning-text); font-size: var(--fs-caption); line-height: 1.6; background: var(--tint-warning); }
.choice-row { display: flex; gap: var(--sp-2); flex-wrap: wrap; }
.choice-pill { padding: 7px 16px; border: 1px solid var(--line-2); border-radius: var(--r-pill); color: var(--app-text-secondary); font-size: var(--fs-caption); }
.choice-pill.active { border-color: var(--app-color-primary); color: var(--app-color-primary); background: var(--tint-primary); font-weight: 600; }
.agreement-row { padding: var(--sp-4) var(--sp-1) 0; }
.submit-row { padding: var(--sp-4) 0; }
</style>
