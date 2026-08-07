<script setup lang="ts">
import type { StationSummary } from '@/api/catalog'
import type { CourierAdmission } from '@/api/delivery'
import { onShow } from '@dcloudio/uni-app'
import { computed, reactive, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { catalogApi } from '@/api/catalog'
import { ContractError } from '@/api/common'
import { deliveryApi } from '@/api/delivery'
import { currentMode } from '@/api/runtime'
import AppNavbar from '@/components/app-navbar.vue'
import { useAccountStore } from '@/store/account'
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
 * <p>delivery 接真时另加一道闸：服务端没有自助提交端点（仅 /detail，建档走 PC 人工链路），
 * 提交必定 fail-closed。让用户填完整张表再撞报错是最差的交互，故 real 下直接不渲染表单，
 * 改以下方提示说明正确路径。</p>
 */
const selfSubmitSupported = currentMode('delivery') !== 'real'
const showForm = computed(
  () => selfSubmitSupported && admission.value !== null && [0, 4].includes(admission.value.status),
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
      <wd-loading size="24px" />
      <view class="muted-text">
        准入状态加载中…
      </view>
    </view>

    <view v-else-if="status === 'error'" class="page-section">
      <wd-status-tip image="network" :tip="errorMessage" />
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

          <view v-if="admission.status === 0" class="muted-text">
            <template v-if="selfSubmitSupported">
              尚未提交申请，审核通过后才能接单。
            </template>
            <template v-else>
              暂不支持自助申请，请联系运营开通。
            </template>
          </view>

          <template v-else-if="admission.status === 1">
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
            <view class="muted-text">
              配送任务入口在首页。
            </view>
          </template>

          <view v-else-if="admission.status === 3" class="muted-text">
            已撤销接单资格，恢复请联系运营。
          </view>

          <template v-else>
            <view class="admission-reject">
              驳回原因：{{ admission.rejectReason || '运营未填写原因' }}
            </view>
            <view v-if="!selfSubmitSupported" class="muted-text">
              重新申请请联系运营。
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
  margin-bottom: 6px;
  font-size: 14px;
}

.admission-reject {
  margin-bottom: 6px;
  font-size: 14px;
  color: #fa4350;
}

.declaration-row {
  padding: 12px 4px 0;
}

.submit-row {
  padding: 16px 0 4px;
}
</style>
