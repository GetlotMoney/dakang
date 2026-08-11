<script setup lang="ts">
import type { DeliveryTask, SignPhoto, SignPhotoType } from '@/api/delivery'
import { onLoad } from '@dcloudio/uni-app'
import { computed, reactive, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { ContractError } from '@/api/common'
import { deliveryApi, uploadDeliveryMedia } from '@/api/delivery'
import { canSignDeliveryTask } from '@/api/delivery-normalize'
import { currentMode } from '@/api/runtime'
import AppNavbar from '@/components/app-navbar.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import SignPhotoSlot from '@/components/sign-photo-slot.vue'
import { TASK_STATUS_LABELS } from '@/utils/format'
import { backOr } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '三照签收',
  },
})

// 权威照片时间不由页面提供：提交签收时由原型契约统一记录动作时间（第五轮审计整改）。
/** 确定性原型定位快照：光谷软件园水站种子坐标（30.4586, 114.4276），随三照一并记录。 */
const SIGN_LOCATION_SNAPSHOT = { latitude: 30.4586, longitude: 114.4276 }

const PHOTO_SLOTS: Array<{ type: SignPhotoType, label: SignPhoto['label'] }> = [
  { type: 1, label: '门牌' },
  { type: 2, label: '水品' },
  { type: 3, label: '摆放' },
]

const toast = useToast()
const message = useMessage()

/** delivery 域接真：三照先上传换受控媒体键再签收；定位不做虚假声明。 */
const isDeliveryReal = currentMode('delivery') === 'real'

const taskNo = ref('')
const status = ref<'loading' | 'ready' | 'blocked' | 'error'>('loading')
const errorMessage = ref('')
const task = ref<DeliveryTask | null>(null)
const submitting = ref(false)
const highlightMissing = ref(false)

// 签收草稿：失败（SIGN_PHOTO_INCOMPLETE / TASK_STALE 等）时保留，不推进状态。
const draft = reactive({
  photos: { 1: '', 2: '', 3: '' } as Record<SignPhotoType, string>,
  actualDeliveryCount: 1,
  actualReturnCount: 0,
})

const missingLabels = computed(
  () => PHOTO_SLOTS.filter(slot => !draft.photos[slot.type]).map(slot => slot.label),
)

onLoad(async (query) => {
  taskNo.value = query?.taskNo ?? ''
  await refresh(true)
})

async function refresh(initializeDraft = false) {
  if (!taskNo.value) {
    errorMessage.value = '无法识别该任务，请返回任务列表重新打开'
    status.value = 'error'
    return
  }
  try {
    const detail = await deliveryApi.getTaskDetail(taskNo.value)
    task.value = detail
    // 阻断判定走契约纯函数（与包A「当前任务不能签收」拒因同源），页面不写第二份状态规则
    if (!canSignDeliveryTask(detail)) {
      status.value = 'blocked'
      return
    }
    if (initializeDraft) {
      draft.actualDeliveryCount = detail.plannedDeliveryCount
      draft.actualReturnCount = detail.plannedReturnCount
    }
    status.value = 'ready'
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
  // 前置校验：三照缺一不可，与接口 SIGN_PHOTO_INCOMPLETE 强校验同口径，并高亮缺失位。
  if (missingLabels.value.length) {
    highlightMissing.value = true
    toast.error(`三照缺一不可，缺少：${missingLabels.value.join('、')}`)
    return
  }
  if (draft.actualDeliveryCount < 1 || draft.actualReturnCount < 0) {
    toast.error('实际配送或回收数量不合法')
    return
  }
  try {
    await message.confirm({
      title: '三照签收',
      msg: '确认完成签收？签收后订单即完成，无法撤销。',
    })
  }
  catch {
    return
  }
  submitting.value = true
  try {
    // real：三照先上传换受控媒体键（recordRef=mediaKey），签收事务内原子占用；
    //       定位未接真实采集，按「未记录」提交，绝不用固定坐标冒充真实定位。
    // mock：保持原样——本地记录号 + 确定性原型定位快照。
    const photos = isDeliveryReal
      ? await Promise.all(PHOTO_SLOTS.map(async slot => ({
          type: slot.type,
          label: slot.label,
          recordRef: await uploadDeliveryMedia(draft.photos[slot.type], 'sign'),
          previewUrl: draft.photos[slot.type],
          evidenceMode: 'real' as const,
        })))
      : PHOTO_SLOTS.map(slot => ({
          type: slot.type,
          label: slot.label,
          recordRef: `LOCAL-${current.taskNo}-${slot.type}`,
          previewUrl: draft.photos[slot.type],
          latitude: SIGN_LOCATION_SNAPSHOT.latitude,
          longitude: SIGN_LOCATION_SNAPSHOT.longitude,
          evidenceMode: 'prototype' as const,
        }))
    await deliveryApi.signTask({
      taskNo: current.taskNo,
      expectedVersion: current.version,
      actualDeliveryCount: draft.actualDeliveryCount,
      actualReturnCount: draft.actualReturnCount,
      photos,
      locationStatus: isDeliveryReal ? 'unrecorded' : 'prototype-snapshot',
    })
    // 签收结果由 D03 状态与三照证据承载，不用 Toast 宣布关键结果。
    uni.navigateBack()
  }
  catch (error) {
    if (error instanceof ContractError && error.code === 'TASK_STALE') {
      toast.warning('任务状态已变化，已刷新最新状态')
      await refresh()
    }
    else {
      toast.error(error instanceof ContractError ? error.message : '签收提交失败，草稿已保留')
    }
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="page-shell screen-d04">
    <AppNavbar title="三照签收" back-to="D03" />
    <wd-toast />
    <wd-message-box />
    <AppPrototypeNotice text="签收提交后无法撤销。" />

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
        :tip="`当前状态不能签收：${task ? TASK_STATUS_LABELS[task.taskStatus] : '未知'}`"
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
          <wd-cell title="任务号" :value="task.taskNo" />
          <wd-cell title="收货地址" :label="task.receiveAddress" />
          <wd-cell title="计划配送 / 预计回收" :value="`${task.plannedDeliveryCount} / ${task.plannedReturnCount}`" />
        </wd-cell-group>
      </view>

      <view class="page-section">
        <view class="block-title">
          三照证据（缺一不可）
        </view>
        <SignPhotoSlot
          v-for="slot in PHOTO_SLOTS"
          :key="slot.type"
          v-model="draft.photos[slot.type]"
          :label="slot.label"
          :missing="highlightMissing && !draft.photos[slot.type]"
          recorded-tag="已选择"
        />
      </view>

      <view class="page-section">
        <wd-cell-group title="数量确认" border>
          <wd-cell title="实际配送数量" center>
            <view class="count-value">
              <wd-input-number v-model="draft.actualDeliveryCount" :min="1" />
            </view>
          </wd-cell>
          <wd-cell title="实际回收数量" center>
            <view class="count-value">
              <wd-input-number v-model="draft.actualReturnCount" :min="0" />
            </view>
          </wd-cell>
        </wd-cell-group>
      </view>

      <view class="page-section">
        <wd-button block size="large" :loading="submitting" @click="handleSubmit">
          提交签收
        </wd-button>
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

.block-title {
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
}

.count-value {
  display: flex;
  justify-content: flex-end;
}
</style>
