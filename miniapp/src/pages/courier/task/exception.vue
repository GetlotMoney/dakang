<script setup lang="ts">
import type { DeliveryExceptionRecord, DeliveryTask } from '@/api/delivery'
import { onLoad } from '@dcloudio/uni-app'
import { reactive, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { ContractError } from '@/api/common'
import { deliveryApi, uploadDeliveryMedia } from '@/api/delivery'
import { canReportDeliveryException } from '@/api/delivery-normalize'
import { currentMode } from '@/api/runtime'
import AppNavbar from '@/components/app-navbar.vue'
import EvidencePicker from '@/components/evidence-picker.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import { TASK_STATUS_LABELS } from '@/utils/format'
import { backOr } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '配送异常',
  },
})

const REASON_OPTIONS: Array<{ value: DeliveryExceptionRecord['reason'], label: string }> = [
  { value: 'UNREACHABLE', label: '无法联系用户' },
  { value: 'ADDRESS', label: '地址问题' },
  { value: 'QUANTITY', label: '数量问题' },
  { value: 'DAMAGED', label: '货品破损' },
  { value: 'OTHER', label: '其他' },
]

const toast = useToast()
const message = useMessage()

/** delivery 域接真：举证照片先上传换受控媒体键再上报。 */
const isDeliveryReal = currentMode('delivery') === 'real'

const taskNo = ref('')
const status = ref<'loading' | 'ready' | 'blocked' | 'error'>('loading')
const errorMessage = ref('')
const task = ref<DeliveryTask | null>(null)
const submitting = ref(false)
const form = ref()

const model = reactive({
  reason: '' as '' | DeliveryExceptionRecord['reason'],
  description: '',
  photos: [] as string[],
})

onLoad(async (query) => {
  taskNo.value = query?.taskNo ?? ''
  await refresh()
})

async function refresh() {
  if (!taskNo.value) {
    errorMessage.value = '缺少任务参数 taskNo'
    status.value = 'error'
    return
  }
  try {
    const detail = await deliveryApi.getTaskDetail(taskNo.value)
    task.value = detail
    // 阻断判定走契约纯函数（包A EXCEPTION_NOT_ALLOWED 同口径：仅履约中 2/3/4 可上报）
    status.value = canReportDeliveryException(detail) ? 'ready' : 'blocked'
  }
  catch (error) {
    task.value = null
    errorMessage.value = error instanceof ContractError ? error.message : '任务详情加载失败'
    status.value = 'error'
  }
}

async function handleSubmit() {
  const current = task.value
  if (!current || submitting.value) {
    return
  }
  const { valid } = await form.value.validate()
  if (!valid) {
    return
  }
  try {
    await message.confirm({
      title: '上报配送异常',
      msg: '异常上报只登记原因、说明与凭证，不改变金额、不退款、不改设备状态。确认上报？',
    })
  }
  catch {
    return
  }
  submitting.value = true
  try {
    // real：举证照片先上传换受控媒体键；mock：保持本地记录号原样
    const evidenceRefs = isDeliveryReal
      ? await Promise.all(model.photos.map(photo => uploadDeliveryMedia(photo, 'exception')))
      : model.photos.map((_, index) => `DEX-EV-${index + 1}`)
    await deliveryApi.reportException({
      taskNo: current.taskNo,
      expectedVersion: current.version,
      reason: model.reason as DeliveryExceptionRecord['reason'],
      description: model.description.trim(),
      evidenceRefs,
    })
    // 上报结果由 D03 异常记录区承载，返回后可回看。
    uni.navigateBack()
  }
  catch (error) {
    if (error instanceof ContractError && error.code === 'TASK_STALE') {
      toast.warning('任务状态已变化，已刷新最新状态')
      await refresh()
    }
    else {
      toast.error(error instanceof ContractError ? error.message : '异常上报失败，表单已保留')
    }
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="配送异常" back-to="D03" />
    <wd-toast />
    <wd-message-box />
    <AppPrototypeNotice domain="delivery" />

    <view v-if="status === 'loading'" class="page-section state-block">
      <wd-loading size="24px" />
      <view class="muted-text">
        任务信息加载中…
      </view>
    </view>

    <view v-else-if="status === 'error'" class="page-section">
      <wd-status-tip image="network" :tip="errorMessage">
        <template #bottom>
          <view class="status-actions">
            <wd-button plain @click="backOr('D01')">
              返回任务中心
            </wd-button>
          </view>
        </template>
      </wd-status-tip>
    </view>

    <view v-else-if="status === 'blocked'" class="page-section">
      <wd-status-tip
        image="content"
        :tip="`当前任务状态不能上报配送异常（需已接单/配送中/待确认，当前：${task ? TASK_STATUS_LABELS[task.taskStatus] : '未知'}）`"
      >
        <template #bottom>
          <view class="status-actions">
            <wd-button plain @click="backOr('D01')">
              返回
            </wd-button>
          </view>
        </template>
      </wd-status-tip>
    </view>

    <template v-else-if="task">
      <view class="page-section">
        <wd-cell-group title="任务摘要" border>
          <wd-cell title="任务号" :value="`${task.taskNo}（状态版本 v${task.version}）`" />
          <wd-cell title="当前状态" :value="TASK_STATUS_LABELS[task.taskStatus]" />
          <wd-cell title="收货地址" :label="task.receiveAddress" />
        </wd-cell-group>
      </view>

      <view class="page-section">
        <wd-form ref="form" :model="model">
          <wd-cell-group title="异常上报" border>
            <wd-picker
              v-model="model.reason"
              label="异常类型"
              prop="reason"
              required
              :columns="REASON_OPTIONS"
              placeholder="请选择异常类型"
              :rules="[{ required: true, message: '请选择异常类型' }]"
            />
            <wd-textarea
              v-model="model.description"
              label="异常说明"
              prop="description"
              required
              :maxlength="200"
              show-word-limit
              auto-height
              placeholder="请描述异常情况（必填）"
              :rules="[{ required: true, message: '请填写异常说明' }]"
            />
            <wd-cell title="现场凭证" vertical>
              <EvidencePicker v-model="model.photos" domain="delivery" />
            </wd-cell>
          </wd-cell-group>

          <view class="muted-text form-hint">
            异常上报不改变金额、不退款、不改设备状态（配送红线）；材料供 PC 运营与用户回看。
          </view>
          <view class="submit-row">
            <wd-button block size="large" :loading="submitting" @click="handleSubmit">
              提交异常上报
            </wd-button>
          </view>
        </wd-form>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.state-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 0;
}

.status-actions {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}

.form-hint {
  padding: 8px 4px 0;
  line-height: 1.6;
}

.submit-row {
  padding: 16px 0 4px;
}
</style>
