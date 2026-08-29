<script setup lang="ts">
import type { StationSummary } from '@/api/catalog'
import AppPageState from '@/components/app-page-state.vue'
import type { CourierAdmission } from '@/api/delivery'
import { onShow } from '@dcloudio/uni-app'
import { computed, reactive, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { catalogApi } from '@/api/catalog'
import { ContractError } from '@/api/common'
import { deliveryApi } from '@/api/delivery'
import AppNavbar from '@/components/app-navbar.vue'
import { useAccountStore } from '@/store/account'
import { goTo } from '@/utils/navigation'
import {
  ADMISSION_STATUS_LABELS,
  ADMISSION_STATUS_TONES,
  formatBizTime,
} from '@/utils/format'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '配送准入',
  },
})

const accountStore = useAccountStore()
const toast = useToast()
const message = useMessage()

const status = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
const admission = ref<CourierAdmission | null>(null)
const stations = ref<StationSummary[]>([])
const submitting = ref(false)
const form = ref()

const model = reactive({
  applicantName: '',
  phone: '',
  requestedRegion: '',
  stationIds: [] as string[],
  declaration: false,
})

/**
 * 只有未提交(0)与驳回(4)允许（重新）提交，与契约 COURIER_ADMISSION_NOT_ALLOWED 口径一致。
 *
 * <p>S3 起服务端提供自助提交端点（/mini/delivery/admission/submit，主体恒取会话、
 * 驳回复用原记录），real 模式表单放开；状态闸与水站合法性由服务端最终裁决。</p>
 */
// S3 起自助提交端点常在，selfSubmitSupported 这个恒 true 的开关连同它守着的两条
// v-else 分支（「暂不支持自助申请，请联系运营开通。」「重新申请请联系运营。」）一并删除——
// 那两句在任何构建里都不可达，留着只会让人以为存在一条走不通的路径。
const showForm = computed(
  () => admission.value !== null && [0, 4].includes(admission.value.status),
)

const stationNameById = computed(
  () => Object.fromEntries(stations.value.map(item => [item.id, item.stationName])),
)

const requestedScopeText = computed(() => {
  const record = admission.value
  if (!record) {
    return '—'
  }
  const parts: string[] = []
  if (record.requestedRegion) {
    parts.push(record.requestedRegion)
  }
  if (record.requestedStationIds.length) {
    parts.push(record.requestedStationIds.map(id => stationNameById.value[id] ?? id).join('、'))
  }
  return parts.length ? parts.join(' / ') : '未填写'
})

/** 已启用时优先展示账号上下文中的已授权范围（courierScope），无则回退申请范围。 */
const grantedScopeText = computed(() => {
  const scope = accountStore.context?.courierScope
  if (!scope) {
    return requestedScopeText.value
  }
  const names = scope.stationIds.map(id => stationNameById.value[id] ?? id).join('、')
  return `${scope.serviceRegion || '服务区域未配置'} · ${scope.stationIds.length} 个水站（${names}）`
})

onShow(refresh)

async function refresh() {
  status.value = 'loading'
  try {
    if (!accountStore.restored) {
      await accountStore.restoreSession().catch(() => null)
    }
    const [record, stationList] = await Promise.all([
      deliveryApi.getCourierAdmission(),
      catalogApi.listStations(),
    ])
    admission.value = record
    stations.value = stationList
    if ([0, 4].includes(record.status)) {
      model.applicantName = record.applicantName
      model.requestedRegion = record.requestedRegion ?? ''
      model.stationIds = [...record.requestedStationIds]
      // 记录仅保留脱敏号，重新提交时手机号必须重新填写，不回填。
      model.phone = ''
      model.declaration = false
    }
    status.value = 'ready'
  }
  catch (error) {
    errorMessage.value = error instanceof ContractError ? error.message : '配送准入状态加载失败'
    status.value = 'error'
  }
}

async function handleSubmit() {
  if (submitting.value) {
    return
  }
  const { valid } = await form.value.validate()
  if (!valid) {
    return
  }
  // 区域与水站至少一项 + 必要声明：契约 COURIER_SCOPE_REQUIRED / COURIER_ADMISSION_INVALID 的前置校验。
  if (!model.stationIds.length && !model.requestedRegion.trim()) {
    toast.error('申请服务区域或水站至少填写一项')
    return
  }
  if (!model.declaration) {
    toast.error('请先勾选真实性与配送规范承诺')
    return
  }
  try {
    await message.confirm({
      title: '提交准入申请',
      msg: '提交后需审核通过才能接单。确认提交？',
    })
  }
  catch {
    return
  }
  submitting.value = true
  try {
    admission.value = await deliveryApi.submitCourierAdmission({
      applicantName: model.applicantName,
      phone: model.phone,
      requestedStationIds: [...model.stationIds],
      requestedRegion: model.requestedRegion.trim() || undefined,
      declarationAccepted: model.declaration,
    })
    // 演示环境会在提交事务内直接启用配送能力；立即刷新上下文，避免首页继续使用旧缓存。
    await accountStore.refreshSession()
    toast.success(admission.value.status === 2 ? '配送能力已自动启用' : '准入申请已提交')
    model.phone = ''
    model.declaration = false
  }
  catch (error) {
    toast.error(error instanceof ContractError ? error.message : '提交失败，请重试')
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="配送准入" back-to="U03" />
    <wd-toast />
    <wd-message-box />

    <view v-if="status === 'loading'" class="page-section state-block">
      <AppPageState state="loading" :row-col="[1, 1, { width: '60%' }]" />
    </view>

    <view v-else-if="status === 'error'" class="page-section">
      <AppPageState state="error" :message="errorMessage" />
    </view>

    <template v-else-if="admission">
      <view class="page-section">
        <wd-card custom-class="admission-card">
          <template #title>
            <view class="card-title-row">
              <view>准入状态</view>
              <wd-tag :type="ADMISSION_STATUS_TONES[admission.status]" plain>
                {{ ADMISSION_STATUS_LABELS[admission.status] }}
              </wd-tag>
            </view>
          </template>

          <!-- status=0 不再单独出正文：状态 tag 已经写着「未提交」，
               而「审核通过后才能接单」是流程讲解；下方的申请表单本身就是下一步。 -->
          <template v-if="admission.status === 1">
            <view class="admission-line">
              申请人：{{ admission.applicantName }} · {{ admission.maskedPhone }}
            </view>
            <view class="admission-line">
              提交时间：{{ formatBizTime(admission.submittedTime) }}
            </view>
            <view class="admission-line">
              申请范围：{{ requestedScopeText }}
            </view>
          </template>

          <template v-else-if="admission.status === 2">
            <view class="admission-line">
              服务范围：{{ grantedScopeText }}
            </view>
            <!-- 原来这里写「配送任务入口在首页。」——用一句话回答「那我该去哪」，
                 正确解法是把去处本身放在这里 -->
            <view class="admission-enter">
              <wd-button size="small" plain icon="goods" @click="goTo('D01')">
                进入任务中心
              </wd-button>
            </view>
          </template>

          <view v-else-if="admission.status === 3" class="muted-text">
            恢复接单资格请联系运营
          </view>

          <template v-else>
            <view class="admission-reject">
              驳回原因：{{ admission.rejectReason || '运营未填写原因' }}
            </view>
          </template>
        </wd-card>
      </view>

      <view v-if="showForm" class="page-section">
        <wd-form ref="form" :model="model">
          <wd-cell-group title="准入申请" border>
            <wd-input
              v-model="model.applicantName"
              label="姓名"
              prop="applicantName"
              required
              clearable
              placeholder="请输入申请人姓名"
              :rules="[{ required: true, message: '请填写申请人姓名' }]"
            />
            <wd-input
              v-model="model.phone"
              label="手机号"
              prop="phone"
              required
              clearable
              type="number"
              :maxlength="11"
              placeholder="请输入 11 位手机号"
              :rules="[{ required: true, pattern: /^1\d{10}$/, message: '请输入 11 位手机号' }]"
            />
            <wd-input
              v-model="model.requestedRegion"
              label="服务区域"
              prop="requestedRegion"
              clearable
              placeholder="选填，与水站至少填一项"
            />
            <wd-cell title="申请服务水站" vertical>
              <wd-checkbox-group v-model="model.stationIds">
                <wd-checkbox
                  v-for="station in stations"
                  :key="station.id"
                  :model-value="station.id"
                >
                  {{ station.stationName }}
                </wd-checkbox>
              </wd-checkbox-group>
            </wd-cell>
          </wd-cell-group>

          <view class="declaration-row">
            <wd-checkbox v-model="model.declaration">
              我承诺信息真实并遵守配送规范
            </wd-checkbox>
          </view>
          <view class="submit-row">
            <wd-button block size="large" :loading="submitting" @click="handleSubmit">
              提交准入申请
            </wd-button>
          </view>
        </wd-form>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.state-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 0;
}

.admission-line {
  margin-bottom: var(--sp-2);
  overflow: hidden;
  color: var(--app-text-primary);
  font-size: var(--fs-caption);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.admission-enter {
  margin-top: var(--sp-3);
}

.admission-reject {
  margin-bottom: 6px;
  font-size: 14px;
  color: var(--app-color-danger);
}

.declaration-row {
  padding: 12px 4px 0;
}

.submit-row {
  padding: 16px 0 4px;
}
</style>
