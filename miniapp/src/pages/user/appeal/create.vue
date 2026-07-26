<script setup lang="ts">
import type { DeliveryTask } from '@/api/delivery'
import type { DeliveryAppeal, OrderDetail } from '@/api/order'
import { onLoad } from '@dcloudio/uni-app'
import { computed, reactive, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { ContractError } from '@/api/common'
import { uploadDeliveryMedia } from '@/api/delivery'
import { appealDeadlineOf, appealWindowState, canCreateDeliveryAppeal } from '@/api/delivery-normalize'
import { orderApi } from '@/api/order'
import { currentMode } from '@/api/runtime'
import AppNavbar from '@/components/app-navbar.vue'
import EvidencePicker from '@/components/evidence-picker.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import { formatBizTime, formatFen, TASK_STATUS_LABELS } from '@/utils/format'
import { backOr, redirectTo } from '@/utils/navigation'
import { nowBusinessTime } from '@/utils/recharge-pay'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '配送申诉',
  },
})

/** 原型固定当前时间（Scenario now=20260716180000），页面仅作展示口径。 */
const PROTOTYPE_NOW = '20260716180000'

const REASON_OPTIONS: Array<{ value: DeliveryAppeal['reason'], label: string }> = [
  { value: 'QUANTITY', label: '数量不符' },
  { value: 'QUALITY', label: '水质问题' },
  { value: 'DAMAGE', label: '货品破损' },
  { value: 'PLACEMENT', label: '摆放问题' },
  { value: 'OTHER', label: '其他' },
]

const toast = useToast()
const message = useMessage()

/** delivery 域接真：申诉创建/凭证上传打真实后端；窗口提示按设备时钟，最终以服务端校验为准。 */
const isDeliveryReal = currentMode('delivery') === 'real'

const orderNo = ref('')
const taskNo = ref('')
const status = ref<'loading' | 'ready' | 'blocked' | 'error'>('loading')
const errorMessage = ref('')
const order = ref<OrderDetail | null>(null)
const task = ref<DeliveryTask | null>(null)
const submitting = ref(false)
const form = ref()

const model = reactive({
  reason: '' as '' | DeliveryAppeal['reason'],
  description: '',
  receivedCount: 0,
  photos: [] as string[],
})

/** 申诉截止：优先服务端签收事务落定的权威值（real）；Mock 数据按签收+24h 派生（蓝图 §10 U09）。 */
const appealDeadline = computed(() => {
  const currentTask = task.value
  return currentTask ? appealDeadlineOf(currentTask) ?? '' : ''
})
/** 超期提示：real 按设备时钟、mock 按原型固定时间；是否放行只以服务端提交校验为准。 */
const deadlineExpired = computed(() => {
  const currentTask = task.value
  if (!currentTask) {
    return false
  }
  return appealWindowState(currentTask, isDeliveryReal ? nowBusinessTime() : PROTOTYPE_NOW) === 'expired'
})

onLoad(async (query) => {
  orderNo.value = query?.orderNo ?? ''
  taskNo.value = query?.taskNo ?? ''
  await refresh()
})

async function refresh() {
  if (!orderNo.value || !taskNo.value) {
    errorMessage.value = '缺少订单或任务参数（orderNo / taskNo）'
    status.value = 'error'
    return
  }
  try {
    const [orderDetail, deliveryTask] = await Promise.all([
      orderApi.getOrderDetail(orderNo.value),
      orderApi.getMyDeliveryTask(orderNo.value),
    ])
    if (!deliveryTask || deliveryTask.taskNo !== taskNo.value) {
      errorMessage.value = '配送任务不存在或与订单不匹配'
      status.value = 'error'
      return
    }
    order.value = orderDetail
    task.value = deliveryTask
    // 阻断判定走契约纯函数（包A「只有已签收订单可以发起申诉」拒因同源）
    if (!canCreateDeliveryAppeal(deliveryTask)) {
      status.value = 'blocked'
      return
    }
    model.receivedCount = deliveryTask.actualDeliveryCount ?? deliveryTask.plannedDeliveryCount
    status.value = 'ready'
  }
  catch (error) {
    errorMessage.value = error instanceof ContractError ? error.message : '订单信息加载失败'
    status.value = 'error'
  }
}

async function handleSubmit() {
  const currentTask = task.value
  if (!currentTask || submitting.value) {
    return
  }
  const { valid } = await form.value.validate()
  if (!valid) {
    return
  }
  if (model.receivedCount < 0) {
    toast.error('实收数量不能小于 0')
    return
  }
  try {
    await message.confirm({
      title: '提交配送申诉',
      msg: '提交后任务转入申诉中（5→7），等待运营裁决；申诉只登记诉求与证据，不直接产生退款或补偿。确认提交？',
    })
  }
  catch {
    return
  }
  submitting.value = true
  try {
    // real：凭证照片先上传换受控媒体键；mock：保持本地记录号原样
    const evidenceRefs = isDeliveryReal
      ? await Promise.all(model.photos.map(photo => uploadDeliveryMedia(photo, 'appeal')))
      : model.photos.map((_, index) => `APPEAL-EV-${index + 1}`)
    await orderApi.createDeliveryAppeal({
      orderNo: orderNo.value,
      taskNo: currentTask.taskNo,
      reason: model.reason as DeliveryAppeal['reason'],
      description: model.description.trim(),
      receivedCount: model.receivedCount,
      evidenceRefs,
    })
    // 成功合同：redirect 到 U06?focus=appeal 回看申诉区块（蓝图 §6.6）。
    redirectTo('U06', { orderNo: orderNo.value, focus: 'appeal' })
  }
  catch (error) {
    // APPEAL_WINDOW_EXPIRED / APPEAL_ALREADY_EXISTS 等契约原因原样展示并保留表单。
    toast.error(error instanceof ContractError ? error.message : '申诉提交失败，请重试')
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="配送申诉" back-to="U02" />
    <wd-toast />
    <wd-message-box />
    <AppPrototypeNotice domain="delivery" />

    <view v-if="status === 'loading'" class="page-section state-block">
      <wd-loading size="24px" />
      <view class="muted-text">
        订单信息加载中…
      </view>
    </view>

    <view v-else-if="status === 'error'" class="page-section">
      <wd-status-tip image="network" :tip="errorMessage">
        <template #bottom>
          <view class="status-actions">
            <wd-button plain @click="backOr('U02')">
              返回订单
            </wd-button>
          </view>
        </template>
      </wd-status-tip>
    </view>

    <view v-else-if="status === 'blocked'" class="page-section">
      <wd-status-tip
        image="content"
        :tip="`只有已签收订单可发起申诉（当前任务状态：${task ? TASK_STATUS_LABELS[task.taskStatus] : '未知'}）`"
      >
        <template #bottom>
          <view class="status-actions">
            <wd-button plain @click="backOr('U02')">
              返回订单
            </wd-button>
          </view>
        </template>
      </wd-status-tip>
    </view>

    <template v-else-if="task && order">
      <view class="page-section">
        <wd-cell-group title="订单与签收信息" border>
          <wd-cell title="订单号" :value="order.order.orderNo" />
          <wd-cell title="任务号" :value="task.taskNo" />
          <wd-cell
            title="配送内容"
            :value="`${task.waterTypeName} · ${task.containerSpec}×${task.plannedDeliveryCount}`"
          />
          <wd-cell title="订单金额" :value="formatFen(order.order.orderAmountFen)" />
          <wd-cell title="签收时间" :value="formatBizTime(task.signTime)" />
          <wd-cell title="申诉截止" center>
            <view class="deadline-value">
              <view>{{ formatBizTime(appealDeadline) }}</view>
              <wd-tag :type="deadlineExpired ? 'danger' : 'success'" plain>
                {{ deadlineExpired ? '已超 24 小时' : '窗口内' }}
              </wd-tag>
            </view>
          </wd-cell>
        </wd-cell-group>
        <view class="muted-text header-note">
          {{ isDeliveryReal
            ? '超期提示按本机时间估算；是否超期以服务端提交时校验为准。'
            : '当前原型时间为 2026-07-16 18:00；是否超期以服务端校验为准。' }}
        </view>
      </view>

      <view class="page-section">
        <wd-form ref="form" :model="model">
          <wd-cell-group title="申诉材料" border>
            <wd-picker
              v-model="model.reason"
              label="申诉原因"
              prop="reason"
              required
              :columns="REASON_OPTIONS"
              placeholder="请选择申诉原因"
              :rules="[{ required: true, message: '请选择申诉原因' }]"
            />
            <wd-textarea
              v-model="model.description"
              label="申诉说明"
              prop="description"
              required
              :maxlength="200"
              show-word-limit
              auto-height
              placeholder="请描述问题与诉求（必填）"
              :rules="[{ required: true, message: '请填写申诉说明' }]"
            />
            <wd-cell title="实收数量" center>
              <view class="count-value">
                <wd-input-number v-model="model.receivedCount" :min="0" />
              </view>
            </wd-cell>
            <wd-cell title="申诉凭证" vertical>
              <EvidencePicker v-model="model.photos" domain="delivery" />
            </wd-cell>
          </wd-cell-group>

          <view class="muted-text form-hint">
            申诉只登记诉求与证据；成立/驳回与补偿由 PC 运营裁决，小程序只消费结果。
          </view>
          <view class="submit-row">
            <wd-button block size="large" :loading="submitting" @click="handleSubmit">
              提交申诉
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

.deadline-value {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.header-note {
  margin-top: 8px;
  padding: 0 4px;
}

.count-value {
  display: flex;
  justify-content: flex-end;
}

.form-hint {
  padding: 8px 4px 0;
  line-height: 1.6;
}

.submit-row {
  padding: 16px 0 4px;
}
</style>
