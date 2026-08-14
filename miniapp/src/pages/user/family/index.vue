<script setup lang="ts">
import type { FamilyProfile, FamilyRewardRecord } from '@/api/card'
import type { TagTone } from '@/utils/format'
import { onLoad, onReady } from '@dcloudio/uni-app'
import { reactive, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { ContractError } from '@/api/common'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import { formatBizTime } from '@/utils/format'
import { backOr } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '家庭资料',
  },
})

const toast = useToast()
const message = useMessage()

const REWARD_STATUS_LABELS: Record<FamilyRewardRecord['status'], string> = {
  RULE_ONLY: '规则待确认',
  EXTERNAL_SNAPSHOT: '已记录',
}

const REWARD_STATUS_TONES: Record<FamilyRewardRecord['status'], TagTone> = {
  RULE_ONLY: 'warning',
  EXTERNAL_SNAPSHOT: 'primary',
}

// 2026-08-02 链路落地：家庭资料已接服务端（按账号隔离，自愿填报）。
const activeTab = ref<'profile' | 'reward'>('profile')
const loading = ref(true)
const loadError = ref('')
const profile = ref<FamilyProfile | null>(null)
const rewards = ref<FamilyRewardRecord[]>([])
const privacyAccepted = ref(false)
const submitting = ref(false)
const deleting = ref(false)

const model = reactive<{
  memberCount: number | ''
  waterHabitNote: string
}>({
  memberCount: '',
  waterHabitNote: '',
})

// 隐私弹窗依赖 wd-message-box 挂载完成（onReady）且资料已加载，两个时机各触发一次检查。
const pageReady = ref(false)
const dataLoaded = ref(false)
const consentPrompted = ref(false)

onLoad((query) => {
  if (query?.tab === 'reward') {
    activeTab.value = 'reward'
  }
  load()
})

onReady(() => {
  pageReady.value = true
  maybePromptConsent()
})

async function load() {
  loading.value = true
  try {
    const [profileResult, rewardList] = await Promise.all([
      cardApi.getFamilyProfile(),
      cardApi.listFamilyRewards(),
    ])
    applyProfile(profileResult)
    rewards.value = rewardList
    loadError.value = ''
  }
  catch (error) {
    loadError.value = error instanceof ContractError ? error.message : '家庭资料加载失败'
  }
  finally {
    loading.value = false
    dataLoaded.value = true
    maybePromptConsent()
  }
}

function applyProfile(value: FamilyProfile | null) {
  profile.value = value
  if (value) {
    privacyAccepted.value = true
    model.memberCount = value.memberCount ?? ''
    model.waterHabitNote = value.waterHabitNote ?? ''
  }
}

/** 首次进入且无资料时弹隐私同意（S05.3）；取消则表单保持禁用，可随时重新查看。 */
function maybePromptConsent() {
  if (!pageReady.value || !dataLoaded.value || consentPrompted.value) {
    return
  }
  if (profile.value || privacyAccepted.value) {
    return
  }
  consentPrompted.value = true
  showConsent()
}

function showConsent() {
  message
    .confirm({
      title: '隐私说明',
      msg: '家庭人数与用水习惯为自愿填报，可随时修改或删除。',
      confirmButtonText: '同意并填写',
      cancelButtonText: '暂不同意',
    })
    .then(() => {
      privacyAccepted.value = true
    })
    .catch(() => {
      privacyAccepted.value = false
    })
}

async function handleSave() {
  if (submitting.value) {
    return
  }
  if (!privacyAccepted.value) {
    toast.show('须先同意隐私说明')
    return
  }
  submitting.value = true
  try {
    const saved = await cardApi.saveFamilyProfile({
      privacyAccepted: privacyAccepted.value,
      memberCount: model.memberCount === '' ? undefined : Number(model.memberCount),
      waterHabitNote: model.waterHabitNote.trim() || undefined,
    })
    applyProfile(saved)
    toast.success('已保存')
  }
  catch (error) {
    toast.error(error instanceof ContractError ? error.message : '保存家庭资料失败，请重试')
  }
  finally {
    submitting.value = false
  }
}

async function handleDelete() {
  if (deleting.value) {
    return
  }
  try {
    await message.confirm({
      title: '删除家庭资料',
      msg: '删除后家庭人数与用水习惯备注将清空，可随时重新填写。',
    })
  }
  catch {
    return
  }
  deleting.value = true
  try {
    await cardApi.deleteFamilyProfile()
    profile.value = null
    privacyAccepted.value = false
    model.memberCount = ''
    model.waterHabitNote = ''
    toast.success('已删除家庭资料')
  }
  catch (error) {
    toast.error(error instanceof ContractError ? error.message : '删除家庭资料失败，请重试')
  }
  finally {
    deleting.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="家庭资料" back-to="U03" />
    <wd-toast />
    <wd-message-box />

    <template v-if="loadError">
      <view class="page-section">
        <AppPageState state="error" :message="loadError">
          <template #actions>
            <wd-button plain @click="backOr('U03')">
              返回
            </wd-button>
          </template>
        </AppPageState>
      </view>
    </template>
    <view v-else-if="loading" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, { width: '60%' }]" />
    </view>
    <view v-else class="page-section">
      <wd-tabs v-model="activeTab">
        <wd-tab title="家庭资料" name="profile">
          <view class="tab-panel">
            <view v-if="!privacyAccepted" class="consent-banner">
              <view class="consent-text">
                <wd-icon name="warning" size="16px" color="var(--app-color-warning)" />
                <view>须先同意隐私说明，再填写家庭资料。</view>
              </view>
              <wd-button size="small" plain @click="showConsent">
                查看并同意
              </wd-button>
            </view>

            <wd-cell-group border>
              <wd-cell title="家庭人数" label="选填">
                <wd-input-number
                  v-model="model.memberCount"
                  :min="0"
                  allow-null
                  placeholder="选填"
                  :disabled="!privacyAccepted"
                />
              </wd-cell>
              <wd-textarea
                v-model="model.waterHabitNote"
                label="用水习惯"
                label-width="96px"
                :maxlength="200"
                show-word-limit
                auto-height
                clearable
                :disabled="!privacyAccepted"
                placeholder="选填，如饮水偏好、日常用水时段等"
              />
            </wd-cell-group>

            <view v-if="profile" class="muted-text profile-meta">
              已同意隐私说明（{{ formatBizTime(profile.privacyConsentTime) }}）· 最近更新 {{ formatBizTime(profile.updatedTime) }}
            </view>

            <view class="panel-actions">
              <wd-button
                block
                size="large"
                :loading="submitting"
                :disabled="!privacyAccepted"
                @click="handleSave"
              >
                保存
              </wd-button>
            </view>
            <!-- 删除降到文字级危险动作：与「保存」同为整宽大按钮时两个主动作等重，
                 一个是常规提交、一个不可逆，摆在一起容易点错。二次确认原样保留。 -->
            <view v-if="profile" class="danger-action" @click="handleDelete">
              <text class="danger-action__text">
                {{ deleting ? '删除中…' : '删除家庭资料' }}
              </text>
            </view>
          </view>
        </wd-tab>
        <wd-tab title="奖励记录" name="reward">
          <view class="tab-panel">
            <template v-if="rewards.length">
              <wd-cell-group border>
                <wd-cell
                  v-for="record in rewards"
                  :key="record.recordId"
                  icon="gift"
                  :title="record.ruleName"
                  :label="record.recordTime ? `记录时间 ${formatBizTime(record.recordTime)}` : '暂无发放记录'"
                >
                  <wd-tag :type="REWARD_STATUS_TONES[record.status]" plain>
                    {{ REWARD_STATUS_LABELS[record.status] }}
                  </wd-tag>
                </wd-cell>
              </wd-cell-group>
            </template>
            <!-- 不写「当前暂不支持领取奖励」：这一屏没有任何领取按钮，
                 看不到按钮本身就是「不能领」，不需要再声明一遍。 -->
            <AppPageState v-else state="empty" title="暂无奖励记录" />
          </view>
        </wd-tab>
      </wd-tabs>
    </view>
  </view>
</template>

<style scoped lang="scss">
.tab-panel {
  padding: 12px 0;
}

.consent-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: var(--sp-3);
  padding: var(--sp-3);
  border-radius: var(--r-sm);
  background: var(--tint-warning);
}

.consent-text {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.profile-meta {
  margin-top: 12px;
  padding: 0 4px;
  line-height: 1.6;
}

.panel-actions {
  margin-top: 16px;
}

.danger-action {
  margin-top: var(--sp-5);
  padding: var(--sp-3);
  text-align: center;

  &__text {
    color: var(--app-color-danger);
    font-size: var(--fs-caption);
  }
}
</style>
