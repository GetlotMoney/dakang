<script setup lang="ts">
import type { DeliveryExceptionRecord, DeliveryTask } from '@/api/delivery'
import type { DeliveryAppeal } from '@/api/order'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { computed, reactive, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { ContractError } from '@/api/common'
import { deliveryApi, deliveryPriceLines, uploadDeliveryMedia } from '@/api/delivery'
import {
  canAcceptDeliveryTask,
  canAdvanceDeliveryTask,
  canReportDeliveryException,
  canSignDeliveryTask,
} from '@/api/delivery-normalize'
import { currentMode } from '@/api/runtime'
import AppNavbar from '@/components/app-navbar.vue'
import EvidencePicker from '@/components/evidence-picker.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import { taskDepartConfirmMsg } from '@/components/runtime-notice'
import {
  APPEAL_STATUS_LABELS,
  APPEAL_STATUS_TONES,
  formatBizTime,
  TASK_STATUS_LABELS,
  TASK_STATUS_TONES,
} from '@/utils/format'
import { backOr, goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '配送任务详情',
  },
})

const EXCEPTION_REASON_LABELS: Record<DeliveryExceptionRecord['reason'], string> = {
  UNREACHABLE: '无法联系用户',
  ADDRESS: '地址问题',
  QUANTITY: '数量问题',
  DAMAGED: '货品破损',
  OTHER: '其他',
}

const APPEAL_REASON_LABELS: Record<DeliveryAppeal['reason'], string> = {
  QUANTITY: '数量不符',
  QUALITY: '水质问题',
  DAMAGE: '货品破损',
  PLACEMENT: '摆放问题',
  OTHER: '其他',
}

const toast = useToast()
const message = useMessage()

/** delivery 域接真：任务/申诉/异常来自真实接口，举证照片先上传换受控媒体键。 */
const isDeliveryReal = currentMode('delivery') === 'real'

const taskNo = ref('')
const focusAppeal = ref(false)
const status = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
const task = ref<DeliveryTask | null>(null)
const exceptions = ref<DeliveryExceptionRecord[]>([])
const appeal = ref<DeliveryAppeal | null>(null)
const acting = ref(false)
const evidenceSubmitting = ref(false)

const evidenceModel = reactive({
  description: '',
  photos: [] as string[],
})

const timelineNodes = computed(() => [
  { title: '接单', time: task.value?.acceptTime },
  { title: '离站', time: task.value?.departTime },
  { title: '送达', time: task.value?.arriveTime },
  { title: '签收', time: task.value?.signTime },
])
// active 取最后一个已发生节点的下标：未发生节点保持待办态，不高亮为"进行中"。
const timelineActive = computed(() => timelineNodes.value.filter(node => node.time).length - 1)

onLoad((query) => {
  taskNo.value = query?.taskNo ?? ''
  focusAppeal.value = query?.focus === 'appeal'
})

onShow(refresh)

async function refresh() {
  if (!taskNo.value) {
    errorMessage.value = '缺少任务参数 taskNo'
    status.value = 'error'
    return
  }
  status.value = 'loading'
  try {
    const detail = await deliveryApi.getTaskDetail(taskNo.value)
    task.value = detail
    exceptions.value = await deliveryApi.listTaskExceptions(taskNo.value).catch(() => [])
    appeal.value = detail.taskStatus === 7
      ? await deliveryApi.getTaskAppeal(taskNo.value).catch(() => null)
      : null
    status.value = 'ready'
  }
  catch (error) {
    // TASK_NOT_FOUND / TASK_ACCESS_DENIED 等按契约原因展示错误态，不白屏（蓝图 9.5 详情状态）。
    task.value = null
    errorMessage.value = error instanceof ContractError ? error.message : '任务详情加载失败'
    status.value = 'error'
  }
}

async function handleActionError(error: unknown) {
  if (error instanceof ContractError && error.code === 'TASK_STALE') {
    toast.warning('任务状态已变化，已刷新最新状态')
    await refresh()
    return
  }
  toast.error(error instanceof ContractError ? error.message : '操作失败，请重试')
}

/** 状态动作统一：message-box 二次确认 → 携带当前 version 调接口 → 成功后刷新详情。 */
async function confirmThenAct(title: string, msg: string, action: () => Promise<void>) {
  if (acting.value) {
    return
  }
  try {
    await message.confirm({ title, msg })
  }
  catch {
    return
  }
  acting.value = true
  try {
    await action()
    await refresh()
  }
  catch (error) {
    await handleActionError(error)
  }
  finally {
    acting.value = false
  }
}

function handleAccept() {
  const current = task.value
  if (!current) {
    return
  }
  confirmThenAct(
    '确认接单',
    `接单后由您负责任务 ${current.taskNo} 的配送履约；服务端将再次校验非本人下单、服务范围与任务版本。`,
    async () => {
      await deliveryApi.acceptTask(current.taskNo, current.version)
    },
  )
}

function handleAdvance(targetStatus: 3 | 4) {
  const current = task.value
  if (!current) {
    return
  }
  // 离站口径按 delivery 模式取自 runtime-notice：real 由服务端记录离站时间且不采集定位，mock 保持原型快照口径。
  const copy = targetStatus === 3
    ? { title: '确认离站', msg: taskDepartConfirmMsg(currentMode('delivery')) }
    : { title: '确认送达', msg: '确认已送达收货地址？送达后需在三照签收页完成签收确认。' }
  confirmThenAct(copy.title, copy.msg, async () => {
    await deliveryApi.advanceTask(current.taskNo, targetStatus, current.version)
  })
}

function handleCall() {
  // 两种模式都不拨真实电话（记录仅保存脱敏号），不冠以「原型」以免在 real 构建下误标。
  toast.show('不拨打真实电话，仅展示脱敏号码')
}

async function handleAppendEvidence() {
  const current = task.value
  const currentAppeal = appeal.value
  if (!current || !currentAppeal || evidenceSubmitting.value) {
    return
  }
  if (!evidenceModel.description.trim()) {
    toast.error('举证说明不能为空')
    return
  }
  try {
    await message.confirm({
      title: '提交申诉举证',
      msg: '举证材料将与三照一并交由 PC 运营核验，小程序只消费裁决结果。确认提交？',
    })
  }
  catch {
    return
  }
  evidenceSubmitting.value = true
  try {
    // real：举证照片先上传换受控媒体键；mock：保持本地记录号原样
    const evidenceRefs = isDeliveryReal
      ? await Promise.all(evidenceModel.photos.map(photo => uploadDeliveryMedia(photo, 'appeal')))
      : evidenceModel.photos.map((_, index) => `COURIER-EV-${index + 1}`)
    appeal.value = await deliveryApi.appendAppealEvidence({
      taskNo: current.taskNo,
      appealId: currentAppeal.appealId,
      description: evidenceModel.description.trim(),
      evidenceRefs,
    })
    evidenceModel.description = ''
    evidenceModel.photos = []
  }
  catch (error) {
    await handleActionError(error)
  }
  finally {
    evidenceSubmitting.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="配送任务详情" back-to="D01" />
    <wd-toast />
    <wd-message-box />
    <AppPrototypeNotice domain="delivery" />

    <view v-if="status === 'loading'" class="page-section state-block">
      <wd-loading size="24px" />
      <view class="muted-text">
        任务详情加载中…
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

    <view v-else-if="task" class="detail-body">
      <view class="page-section">
        <wd-card custom-class="detail-card">
          <view class="status-header">
            <view class="status-title">
              {{ TASK_STATUS_LABELS[task.taskStatus] }}
            </view>
            <wd-tag :type="TASK_STATUS_TONES[task.taskStatus]" plain>
              状态版本 v{{ task.version }}
            </wd-tag>
          </view>
          <view class="muted-text">
            {{ task.taskNo }} · {{ task.stationName }}
          </view>
        </wd-card>
      </view>

      <!-- 申诉区块（状态 7）；focus=appeal 时通过 order 置顶并显示定位标记 -->
      <view
        v-if="task.taskStatus === 7"
        class="page-section"
        :class="{ 'section-focus-first': focusAppeal }"
      >
        <wd-card custom-class="detail-card">
          <template #title>
            <view class="card-title-row">
              <view>申诉处理（等待运营裁决）</view>
              <wd-tag v-if="focusAppeal" type="danger" plain>
                消息定位
              </wd-tag>
            </view>
          </template>
          <template v-if="appeal">
            <wd-cell-group border>
              <wd-cell title="申诉编号" :value="appeal.appealId" />
              <wd-cell title="申诉状态">
                <wd-tag :type="APPEAL_STATUS_TONES[appeal.appealStatus]" plain>
                  {{ APPEAL_STATUS_LABELS[appeal.appealStatus] }}
                </wd-tag>
              </wd-cell>
              <wd-cell title="申诉原因" :value="APPEAL_REASON_LABELS[appeal.reason]" />
              <wd-cell title="用户说明" :label="appeal.description" />
              <wd-cell title="用户实收数量" :value="`${appeal.receivedCount}`" />
              <wd-cell title="用户证据" :value="`${appeal.evidenceRefs.length} 份`" />
              <wd-cell title="申诉时间" :value="formatBizTime(appeal.createTime)" />
            </wd-cell-group>

            <view v-if="appeal.courierEvidences?.length" class="evidence-list">
              <view class="block-subtitle">
                已提交举证
              </view>
              <view
                v-for="(item, index) in appeal.courierEvidences"
                :key="index"
                class="evidence-list-item"
              >
                <view class="evidence-list-desc">
                  {{ item.description }}
                </view>
                <view class="muted-text">
                  {{ formatBizTime(item.time) }} · 凭证 {{ item.evidenceRefs.length }} 张{{ isDeliveryReal ? '（受控媒体）' : '（mock-recorded）' }}
                </view>
              </view>
            </view>

            <view class="evidence-form">
              <view class="block-subtitle">
                追加举证
              </view>
              <wd-textarea
                v-model="evidenceModel.description"
                :maxlength="200"
                show-word-limit
                auto-height
                placeholder="请说明配送过程与证据情况（必填）"
              />
              <EvidencePicker v-model="evidenceModel.photos" domain="delivery" />
              <wd-button
                block
                :loading="evidenceSubmitting"
                custom-class="evidence-submit"
                @click="handleAppendEvidence"
              >
                提交举证
              </wd-button>
            </view>
          </template>
          <view v-else class="muted-text">
            申诉记录不存在或加载失败。
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-cell-group title="任务信息" border>
          <wd-cell title="订单号" :value="task.orderNo" />
          <wd-cell title="任务号" :value="task.taskNo" />
          <wd-cell title="收货地址" :label="task.receiveAddress" />
          <wd-cell title="联系电话" center clickable @click="handleCall">
            <view class="phone-value">
              <view>{{ task.maskedPhone }}</view>
              <wd-icon name="phone" size="16px" color="#5d87ff" />
            </view>
          </wd-cell>
          <wd-cell title="水种" :value="task.waterTypeName" />
          <wd-cell title="容器规格" :value="task.containerSpec" />
          <wd-cell title="计划配送数量" :value="`${task.plannedDeliveryCount}`" />
          <wd-cell title="预计回收数量" :value="`${task.plannedReturnCount}`" />
          <wd-cell
            v-if="task.actualDeliveryCount !== undefined"
            title="实际配送数量"
            :value="`${task.actualDeliveryCount}`"
          />
          <wd-cell
            v-if="task.actualReturnCount !== undefined"
            title="实际回收数量"
            :value="`${task.actualReturnCount}`"
          />
        </wd-cell-group>
      </view>

      <view class="page-section">
        <!-- D-214 展示分流：payWay=3 呈现「水量抵扣 X L + 配送费」，不把 0 元水费渲染成免费 -->
        <wd-cell-group title="价格快照" border>
          <wd-cell
            v-for="line in deliveryPriceLines(task)"
            :key="line.label"
            :title="line.label"
            :value="line.value"
          />
        </wd-cell-group>
      </view>

      <view class="page-section">
        <wd-card custom-class="detail-card" title="履约时间轴">
          <wd-steps :active="timelineActive" vertical>
            <wd-step
              v-for="node in timelineNodes"
              :key="node.title"
              :title="node.title"
              :description="node.time ? formatBizTime(node.time) : '待推进'"
            />
          </wd-steps>
          <view class="muted-text location-row">
            <wd-icon name="location" size="14px" />
            <view>
              {{ task.locationStatus === 'prototype-snapshot'
                ? '定位：确定性原型快照（固定坐标，不绘制轨迹）'
                : task.locationStatus === 'recorded'
                  ? '定位：已随三照记录'
                  : isDeliveryReal
                    ? '定位：未记录（定位采集未接入，不作虚假声明）'
                    : '定位：未记录（签收时以原型快照记录；外部快照未含定位）' }}
            </view>
          </view>
        </wd-card>
      </view>

      <view v-if="task.signPhotos.length" class="page-section">
        <wd-card custom-class="detail-card" title="三照签收证据">
          <view
            v-for="photo in task.signPhotos"
            :key="photo.type"
            class="sign-photo-item"
          >
            <image
              v-if="photo.previewUrl"
              :src="photo.previewUrl"
              mode="aspectFill"
              class="sign-photo-image"
            />
            <view v-else class="sign-photo-placeholder">
              <wd-icon name="picture" size="24px" color="#8a8f99" />
            </view>
            <view class="sign-photo-meta">
              <view class="sign-photo-label">
                {{ photo.label }}
              </view>
              <view class="muted-text">
                {{ formatBizTime(photo.time) }}
              </view>
              <view v-if="photo.latitude !== undefined && photo.longitude !== undefined" class="muted-text">
                坐标 {{ photo.latitude }}, {{ photo.longitude }}{{ photo.evidenceMode === 'real' ? '' : '（原型快照）' }}
              </view>
              <wd-tag :type="photo.evidenceMode === 'real' ? 'success' : 'warning'" plain>
                {{ photo.evidenceMode === 'prototype'
                  ? 'mock-recorded（未上传云端）'
                  : photo.evidenceMode === 'real' ? '受控媒体已记录' : '外部快照' }}
              </wd-tag>
            </view>
          </view>
          <view v-if="task.signTime" class="muted-text">
            签收时间：{{ formatBizTime(task.signTime) }}
          </view>
        </wd-card>
      </view>

      <view v-if="exceptions.length" class="page-section">
        <wd-card custom-class="detail-card" title="配送异常记录">
          <view
            v-for="record in exceptions"
            :key="record.exceptionId"
            class="exception-item"
          >
            <view class="exception-item-header">
              <wd-tag type="warning" plain>
                {{ EXCEPTION_REASON_LABELS[record.reason] }}
              </wd-tag>
              <view class="muted-text">
                {{ formatBizTime(record.createTime) }}
              </view>
            </view>
            <view class="exception-item-desc">
              {{ record.description }}
            </view>
            <view class="muted-text">
              凭证 {{ record.evidenceRefs.length }} 张{{ isDeliveryReal ? '（受控媒体）' : '（mock-recorded）' }}
            </view>
          </view>
        </wd-card>
      </view>

      <!-- 动作区：按状态渲染唯一主动作，每次只推进一个状态，无跳步按钮（蓝图 §10 D03）；
           可用性判定统一走契约纯函数，与包A 服务端拒因同源，页面不写第二份状态规则 -->
      <view v-if="canAcceptDeliveryTask(task)" class="page-section">
        <wd-button block size="large" :loading="acting" @click="handleAccept">
          接单
        </wd-button>
      </view>
      <view v-else-if="canAdvanceDeliveryTask(task, 3)" class="page-section">
        <wd-button block size="large" :loading="acting" @click="handleAdvance(3)">
          确认离站
        </wd-button>
      </view>
      <view v-else-if="canAdvanceDeliveryTask(task, 4)" class="page-section">
        <wd-button block size="large" :loading="acting" @click="handleAdvance(4)">
          确认送达
        </wd-button>
      </view>
      <view v-else-if="canSignDeliveryTask(task)" class="page-section">
        <wd-button block size="large" @click="goTo('D04', { taskNo: task.taskNo })">
          去三照签收
        </wd-button>
      </view>

      <view v-if="canReportDeliveryException(task)" class="page-section">
        <wd-button block plain type="warning" @click="goTo('D05', { taskNo: task.taskNo })">
          上报配送异常
        </wd-button>
      </view>
    </view>
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

.detail-body {
  display: flex;
  flex-direction: column;
}

.section-focus-first {
  order: -1;
}

.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.status-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.status-title {
  font-size: 22px;
  font-weight: 600;
}

.phone-value {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

.location-row {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 10px;
}

.block-subtitle {
  margin: 12px 0 8px;
  font-size: 14px;
  font-weight: 600;
}

.evidence-list-item {
  padding: 8px 0;
  border-bottom: 1px solid #f0f1f3;
}

.evidence-list-desc {
  margin-bottom: 4px;
  font-size: 14px;
}

.evidence-form {
  margin-top: 4px;
}

:deep(.evidence-submit) {
  margin-top: 12px;
}

.sign-photo-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
}

.sign-photo-image,
.sign-photo-placeholder {
  width: 72px;
  height: 72px;
  border-radius: 6px;
  flex-shrink: 0;
}

.sign-photo-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f0f1f3;
}

.sign-photo-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.sign-photo-label {
  font-size: 14px;
  font-weight: 600;
}

.exception-item {
  padding: 10px 0;
  border-bottom: 1px solid #f0f1f3;

  &:last-of-type {
    border-bottom: none;
  }
}

.exception-item-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.exception-item-desc {
  margin-bottom: 4px;
  font-size: 14px;
}
</style>
