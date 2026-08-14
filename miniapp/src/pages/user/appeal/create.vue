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
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import EvidencePicker from '@/components/evidence-picker.vue'
import { formatBizTime, formatFen, TASK_STATUS_LABELS } from '@/utils/format'
import { backOr, redirectTo } from '@/utils/navigation'
import { nowBusinessTime } from '@/utils/recharge-pay'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '配送申诉',
  },
})

const REASON_OPTIONS: Array<{ value: DeliveryAppeal['reason'], label: string }> = [
  { value: 'QUANTITY', label: '数量不符' },
  { value: 'QUALITY', label: '水质问题' },
  { value: 'DAMAGE', label: '货品破损' },
  { value: 'PLACEMENT', label: '摆放问题' },
  { value: 'OTHER', label: '其他' },
]

const toast = useToast()
const message = useMessage()

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

/** 申诉截止：服务端签收事务落定的权威值。 */
const appealDeadline = computed(() => {
  const currentTask = task.value
  return currentTask ? appealDeadlineOf(currentTask) ?? '' : ''
})
/**
 * 超期提示按设备时钟算——它只决定这行字灰不灰，放行与否一律以服务端提交校验为准。
 * 设备时间可被用户随意改，因此这里算出来的「没超期」不构成任何承诺。
 */
const deadlineExpired = computed(() => {
  const currentTask = task.value
  if (!currentTask) {
    return false
  }
  return appealWindowState(currentTask, nowBusinessTime()) === 'expired'
})

onLoad(async (query) => {
  orderNo.value = query?.orderNo ?? ''
  taskNo.value = query?.taskNo ?? ''
  await refresh()
})

async function refresh() {
  if (!orderNo.value || !taskNo.value) {
    errorMessage.value = '订单信息不完整，请从订单详情重新进入'
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
    // 「申诉不直接产生退款或补偿」是下游政策口径，不改变用户此刻要不要提交这个决定
    await message.confirm({
      title: '提交配送申诉',
      msg: '确认提交申诉？',
    })
  }
  catch {
    return
  }
  submitting.value = true
  try {
    // 凭证照片先上传换受控媒体键，再随申诉提交。
    // 这里曾有一条回落分支直接编造 `APPEAL-EV-1` 这样的键发给后端：照片根本没上传，
    // 而申诉单看起来带着凭证——审核方点开是空的，却已按「有证据」处理。
    const evidenceRefs = await Promise.all(
      model.photos.map(photo => uploadDeliveryMedia(photo, 'appeal')),
    )
    await orderApi.createDeliveryAppeal({
      orderNo: orderNo.value,
      taskNo: currentTask.taskNo,
      reason: model.reason as DeliveryAppeal['reason'],
      description: model.description.trim(),
      receivedCount: model.receivedCount,
      evidenceRefs,
    })
    // 成功合同：redirect 到 U06?focus=appeal 回看申诉区块。
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

    <view v-if="status === 'loading'" class="page-section state-block">
      <AppPageState state="loading" :row-col="[1, 1, { width: '60%' }]" />
    </view>

    <view v-else-if="status === 'error'" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain @click="backOr('U02')">
            返回订单
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <view v-else-if="status === 'blocked'" class="page-section">
      <AppPageState
        state="blocked"
        title="暂不能发起申诉"
        :message="`只有已签收订单可发起申诉（当前任务状态：${task ? TASK_STATUS_LABELS[task.taskStatus] : '未知'}）`"
      >
        <template #actions>
          <wd-button plain @click="backOr('U02')">
            返回订单
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <!-- 已过期用 blocked，不再让表单继续可填可提交。
         原实现里 deadlineExpired 算出来了却只用来给一枚 tag 换颜色，表单照常渲染、
         提交按钮照常可点——用户会走完选原因、写说明、真上传三张照片（换受控媒体键），
         最后才在提交时收到 APPEAL_WINDOW_EXPIRED。判据一字未动，只换渲染落点。 -->
    <view v-else-if="status === 'ready' && deadlineExpired" class="page-section">
      <AppPageState
        state="blocked"
        title="已超过申诉时限"
        :message="`申诉截止 ${formatBizTime(appealDeadline)}`"
      >
        <template #actions>
          <wd-button plain @click="backOr('U02')">
            返回订单
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else-if="task && order">
      <view class="page-section">
        <wd-cell-group title="订单与签收信息" border>
          <wd-cell title="订单号" :value="order.order.orderNo" ellipsis />
          <wd-cell title="任务号" :value="task.taskNo" ellipsis />
          <wd-cell
            title="配送内容"
            :value="`${task.waterTypeName} · ${task.containerSpec}×${task.plannedDeliveryCount}`"
          />
          <wd-cell title="订单金额" :value="formatFen(order.order.orderAmountFen)" />
          <wd-cell title="签收时间" :value="formatBizTime(task.signTime)" />
          <!-- 走到这里必然在窗口内（过期已被上面的 blocked 拦下），不再需要状态 tag；
               整行展开避免日期与 tag 在 121.5px 的 value 列里互相挤断。 -->
          <wd-cell title="申诉截止" :value="formatBizTime(appealDeadline)" vertical />
        </wd-cell-group>
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
              <EvidencePicker v-model="model.photos" />
            </wd-cell>
          </wd-cell-group>

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

.deadline-value {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.count-value {
  display: flex;
  justify-content: flex-end;
}

.submit-row {
  padding: 16px 0 4px;
}
</style>
