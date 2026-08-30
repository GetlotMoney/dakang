<script setup lang="ts">
import type { DeliveryTask } from '@/api/delivery'
import { deliveryPriceLines } from '@/api/delivery'
import type { CardDetail } from '@/api/card'
import type { DeliveryAppeal, OrderDetail, OrderTraceNode } from '@/api/order'
import type { RechargePayStatus } from '@/api/recharge'
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { ContractError } from '@/api/common'
import {
  AFTER_SALE_ACTION_TYPE_LABELS,
  AFTER_SALE_SOURCE_LABELS,
  AFTER_SALE_STATUS_LABELS,
  afterSaleAmountRows,
  afterSaleApi,
  afterSaleResultText,
  canShowCancelEntry,
  isResendOrderNo,
  isResendTaskNo,
  refundSourceText,
} from '@/api/after-sale'
import { cardApi } from '@/api/card'
import { appealDeadlineOf, canCreateDeliveryAppeal } from '@/api/delivery-normalize'
import { orderApi } from '@/api/order'
import { rechargeApi } from '@/api/recharge'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import WechatContactEntry from '@/components/wechat-contact-entry.vue'
import {
  APPEAL_STATUS_LABELS,
  APPEAL_STATUS_TONES,
  COMMAND_STATUS_LABELS,
  formatBizTime,
  formatBizTimeShort,
  formatFen,
  formatMl,
  ORDER_STATUS_LABELS,
  ORDER_STATUS_TONES,
  ORDER_TYPE_LABELS,
  PAY_WAY_LABELS,
  TASK_STATUS_LABELS,
  TASK_STATUS_TONES,
} from '@/utils/format'
import { backOr, goTo } from '@/utils/navigation'
import { continuePayGate, nowBusinessTime, payAndSettle } from '@/utils/recharge-pay'
import {
  isRechargeSettled,
  rechargeNotice,
  rechargePaySourceLabel,
  rechargeProcessingStatusLabel,
} from '@/utils/recharge-presentation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '订单详情',
  },
})

const toast = useToast()
const message = useMessage()

/** 申诉原因中文口径（utils/format 暂无该映射，页面内先行冻结）。 */
const APPEAL_REASON_LABELS: Record<DeliveryAppeal['reason'], string> = {
  QUANTITY: '数量不符',
  QUALITY: '水质问题',
  DAMAGE: '容器破损',
  PLACEMENT: '摆放不当',
  OTHER: '其他',
}

type FocusBlock = '' | 'command' | 'delivery' | 'appeal'

const pageState = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
const errorImage = ref<'content' | 'network'>('network')

const detail = ref<OrderDetail | null>(null)
const issuedCard = ref<CardDetail | null>(null)
const issuedCardError = ref('')

const isPurchaseOrder = computed(
  () => detail.value?.order.recharge?.purchaseMode === 'FIRST_CARD',
)

const rechargeSettled = computed(() => {
  const order = detail.value?.order
  return !!order && isRechargeSettled(order.orderStatus, order.recharge)
})

const isCompletedPurchase = computed(
  () => isPurchaseOrder.value && detail.value?.order.orderStatus === 4 && rechargeSettled.value,
)

const paymentMethodText = computed(() => {
  const order = detail.value?.order
  if (!order) {
    return '—'
  }
  if (order.orderType !== 2) {
    return PAY_WAY_LABELS[order.payWay]
  }
  return rechargePaySourceLabel(order.recharge?.paySource, order.payWay)
})

/**
 * 充值信息展示行。页面不解析 PACKAGE_SNAP（契约 v2 §9.2），只消费已校验的结构化区块；
 * 返回 null 表示数据异常，由模板提示联系客服，绝不用残缺值拼出看似正常的订单。
 */
const rechargeRows = computed<{ label: string, value: string }[] | null>(() => {
  const block = detail.value?.order.recharge
  if (!block || !block.snapshotValid) {
    return null
  }
  const rows: { label: string, value: string }[] = [
    { label: '套餐名称', value: block.packageName ?? '—' },
    { label: '支付金额', value: block.payAmountFen == null ? '—' : formatFen(block.payAmountFen) },
    { label: '支付来源', value: rechargePaySourceLabel(block.paySource, detail.value!.order.payWay) },
  ]
  if ((block.waterMl ?? 0) > 0) {
    rows.push({ label: '到账水量', value: formatMl(block.waterMl!) })
  }
  if ((block.bonusAmountFen ?? 0) > 0) {
    rows.push({ label: '赠送余额', value: formatFen(block.bonusAmountFen!) })
  }
  rows.push({
    label: '套餐有效期',
    value: block.expireDays == null ? '永久有效' : `${block.expireDays} 天`,
  })
  const processingStatus = rechargeProcessingStatusLabel(block.processingStatus)
  if (processingStatus) {
    rows.push({ label: '处理状态', value: processingStatus })
  }
  // 只有完成态且双维流水与快照权益完全一致，才展示到账结果。
  if (rechargeSettled.value) {
    if ((block.flowMlChange ?? 0) > 0) {
      rows.push({ label: '入账水量', value: `+${formatMl(block.flowMlChange!)}` })
    }
    if ((block.flowAmountChange ?? 0) > 0) {
      rows.push({ label: '入账余额', value: `+${formatFen(block.flowAmountChange!)}` })
    }
  }
  if (block.cardBalanceFen != null) {
    rows.push({ label: '水卡当前余额', value: formatFen(block.cardBalanceFen) })
  }
  if (block.cardBalanceMl != null) {
    rows.push({ label: '水卡当前水量', value: formatMl(block.cardBalanceMl) })
  }
  if (block.cardExpireTime) {
    rows.push({ label: '水卡当前有效期', value: formatBizTime(block.cardExpireTime) })
  }
  else if (rechargeSettled.value && block.expireDays == null) {
    rows.push({ label: '水卡当前有效期', value: '永久有效' })
  }
  return rows
})

/** 新卡结果只组合服务端订单证据与 /mini/card/detail，不解析任何范围 JSON。 */
const issuedCardRows = computed<{ label: string, value: string }[] | null>(() => {
  const block = detail.value?.order.recharge
  const card = issuedCard.value
  if (!isCompletedPurchase.value || !block?.snapshotValid || !card) {
    return null
  }
  const benefits: string[] = []
  if ((block.flowMlChange ?? 0) > 0) {
    benefits.push(`水量 +${formatMl(block.flowMlChange!)}`)
  }
  if ((block.flowAmountChange ?? 0) > 0) {
    benefits.push(`余额 +${formatFen(block.flowAmountChange!)}`)
  }
  return [
    { label: '新卡号', value: card.cardNo },
    { label: '卡片类型', value: '虚拟卡' },
    { label: '开卡套餐', value: block.packageName ?? '—' },
    { label: '到账权益', value: benefits.join(' · ') || '—' },
    { label: '卡片有效期', value: card.expireTime ? formatBizTime(card.expireTime) : '永久有效' },
    { label: '可用范围', value: card.scopeDescription },
  ]
})

/**
 * 继续支付：服务端 pay-status 的最新快照。页面不自行推导「还能不能付」（§9.1 矩阵结论只消费）；
 * 拿不到就是 null，按钮一律不显示。
 */
const payStatus = ref<RechargePayStatus | null>(null)
const paying = ref(false)
const continuePay = computed(() => continuePayGate(payStatus.value, nowBusinessTime()))

const rechargeNoticeState = computed(() => {
  const order = detail.value?.order
  return order?.orderType === 2
    ? rechargeNotice(order.orderStatus, rechargeSettled.value)
    : null
})
const task = ref<DeliveryTask | null>(null)
const appeal = ref<DeliveryAppeal | null>(null)
const appealError = ref('')
const focusBlock = ref<FocusBlock>('')
const currentOrderNo = ref('')
let deliveryPollTimer: ReturnType<typeof setTimeout> | null = null
let deliveryPolling = false

const flowCountText = computed(() => {
  const data = detail.value
  if (!data) {
    return ''
  }
  return data.flowCount > 0 ? `钱包流水 ${data.flowCount} 笔` : '未发生扣减'
})

/** 配送任务状态时间轴：任务生成→接单→离站→送达→签收（时间取任务证据字段）。 */
const deliveryTimeline = computed(() => {
  const currentTask = task.value
  if (!currentTask) {
    return []
  }
  return [
    { key: 'created', label: '任务生成', time: detail.value?.order.createTime },
    { key: 'accept', label: '配送员接单', time: currentTask.acceptTime },
    { key: 'depart', label: '取水离站', time: currentTask.departTime },
    { key: 'arrive', label: '送达待确认', time: currentTask.arriveTime },
    { key: 'sign', label: '三照签收', time: currentTask.signTime },
  ]
})

const deliveryActive = computed(() => {
  // active 取最后一个已发生节点的下标：未发生节点保持待办态，不高亮为"进行中"（与 D03 同口径）。
  let last = -1
  deliveryTimeline.value.forEach((node, index) => {
    if (node.time) {
      last = index
    }
  })
  return last
})

/**
 * 申诉窗口展示：优先服务端签收事务落定的 appealDeadline（real 权威值），
 * Mock 数据缺省按签收+24h 派生；实际时限由服务端在提交时二次校验。
 */
const appealDeadlineText = computed(() => {
  const currentTask = task.value
  if (!currentTask || !canCreateDeliveryAppeal(currentTask)) {
    return ''
  }
  const deadline = appealDeadlineOf(currentTask)
  return deadline ? formatBizTime(deadline) : ''
})

const showAppealBlock = computed(() =>
  !!detail.value?.appealId || task.value?.taskStatus === 7 || !!appealError.value,
)

// ==================== 售后与退款（E2E-04 包E：只读） ====================

/** 售后进度只来自服务端下发的区块；缺省即未下发，页面不按订单状态/金额/申诉裁决倒推售后结论。 */
const afterSale = computed(() => detail.value?.afterSale ?? null)
const afterSaleRows = computed(() => (afterSale.value ? afterSaleAmountRows(afterSale.value) : []))
/** 结局文案：未到 actionStatus=3 一律「处理中/待补送」，补送以回签为完成条件。 */
const afterSaleResult = computed(() => (afterSale.value ? afterSaleResultText(afterSale.value) : null))
const afterSaleRefundSource = computed(() =>
  (afterSale.value ? refundSourceText(afterSale.value) : undefined),
)

/** 本单即补送子单：服务端两个值相等才成立，不按零金额等特征猜。 */
const isResendOrder = computed(() =>
  isResendOrderNo(afterSale.value ?? undefined, detail.value?.order.orderNo ?? ''),
)
/** 本单配送任务是补送任务：服务端显式标识或售后动作回填的补送任务号命中。 */
const isResendDelivery = computed(() =>
  task.value?.isResend === true
  || isResendTaskNo(afterSale.value ?? undefined, task.value?.taskNo ?? ''),
)

/** 售后区块显示条件：有售后证据、有取消资格下发，或订单已进入（部分）退款态。 */
const showAfterSaleBlock = computed(() => {
  const order = detail.value?.order
  if (!order) {
    return false
  }
  return !!afterSale.value
    || !!detail.value?.cancelEligibility
    || order.orderStatus === 7
    || order.orderStatus === 8
})

/** 取消入口资格：唯一来源是服务端下发，未下发即隐藏；页面复制判定只会在边界上多给资金入口。 */
const cancelEligibility = computed(() => detail.value?.cancelEligibility)
const showCancelEntry = computed(() => canShowCancelEntry(cancelEligibility.value))
const cancelling = ref(false)

/** trace 色调 → wd-steps 状态映射（steps 仅支持 finished/process/error）。 */
function stepStatus(node: OrderTraceNode): 'finished' | 'error' | undefined {
  if (node.tone === 'danger' || node.tone === 'warning') {
    return 'error'
  }
  if (node.tone === 'success') {
    return 'finished'
  }
  return undefined
}

onLoad((query?: Record<string, string | undefined>) => {
  const orderNo = query?.orderNo
  const focus = query?.focus
  if (focus === 'command' || focus === 'delivery' || focus === 'appeal') {
    focusBlock.value = focus
  }
  if (!orderNo) {
    pageState.value = 'error'
    errorImage.value = 'content'
    errorMessage.value = '请从订单列表进入'
    return
  }
  currentOrderNo.value = orderNo
  void load(orderNo)
})

onShow(() => {
  if (currentOrderNo.value && pageState.value === 'ready') {
    void load(currentOrderNo.value, true)
  }
})

onHide(stopDeliveryPolling)
onUnload(stopDeliveryPolling)

function shouldPollDelivery() {
  return detail.value?.order.orderType === 3
    && !!task.value
    && task.value.taskStatus >= 1
    && task.value.taskStatus <= 4
}

function syncDeliveryPolling() {
  if (!shouldPollDelivery()) {
    stopDeliveryPolling()
    return
  }
  if (deliveryPollTimer !== null) {
    return
  }
  // 单次定时器在每次请求结束后重新安排：避免页面切换或一次慢请求让常驻 interval
  // 丢失后续刷新，配送到达后仍能继续追到三照签收终态。
  deliveryPollTimer = setTimeout(() => {
    deliveryPollTimer = null
    if (!deliveryPolling && currentOrderNo.value) {
      void load(currentOrderNo.value, true)
      return
    }
    syncDeliveryPolling()
  }, 3000)
}

function stopDeliveryPolling() {
  if (deliveryPollTimer !== null) {
    clearTimeout(deliveryPollTimer)
    deliveryPollTimer = null
  }
}

async function load(orderNo: string, silent = false) {
  if (deliveryPolling) {
    return
  }
  deliveryPolling = true
  if (!silent) {
    pageState.value = 'loading'
    issuedCard.value = null
    issuedCardError.value = ''
  }
  try {
    const loaded = await orderApi.getOrderDetail(orderNo)
    detail.value = loaded
    if (loaded.order.orderType === 2) {
      await refreshPayStatus(loaded.order.orderNo)
      if (loaded.order.orderStatus === 4 && loaded.order.recharge?.purchaseMode === 'FIRST_CARD') {
        await loadIssuedCard(loaded)
      }
    }
    if (loaded.order.orderType === 3) {
      task.value = await orderApi.getMyDeliveryTask(orderNo)
    }
    if (loaded.appealId) {
      try {
        appeal.value = await orderApi.getMyDeliveryAppeal(loaded.appealId)
      }
      catch (error) {
        appealError.value = error instanceof ContractError ? error.message : '申诉记录加载失败'
      }
    }
    pageState.value = 'ready'
  }
  catch (error) {
    if (!silent) {
      pageState.value = 'error'
      errorImage.value = error instanceof ContractError && error.code === 'ORDER_NOT_FOUND'
        ? 'content'
        : 'network'
      errorMessage.value = error instanceof ContractError ? error.message : '订单详情加载失败，请重试'
    }
  }
  finally {
    deliveryPolling = false
    syncDeliveryPolling()
  }
}

async function loadIssuedCard(loaded: OrderDetail) {
  const block = loaded.order.recharge
  try {
    if (!block?.snapshotValid || !block.cardId || !block.cardNo
      || loaded.order.cardId !== block.cardId) {
      throw new ContractError('PURCHASE_CARD_EVIDENCE_INVALID', '新卡信息不完整，请联系客服')
    }
    if (!rechargeSettled.value) {
      throw new ContractError('PURCHASE_SETTLEMENT_EVIDENCE_INVALID', '购卡入账信息不完整，请联系客服')
    }
    const card = await cardApi.getCardDetail(block.cardId)
    if (card.cardId !== block.cardId || card.cardNo !== block.cardNo || card.cardType !== 1
      || !card.scopeDescription || card.scopeDescription === '未配置（默认拒绝）') {
      throw new ContractError('PURCHASE_CARD_EVIDENCE_MISMATCH', '新卡信息核对不一致，请联系客服')
    }
    issuedCard.value = card
  }
  catch (error) {
    issuedCardError.value = error instanceof ContractError
      ? error.message
      : '新卡信息加载失败，请稍后重试或联系客服'
  }
}

/** 拉取 pay-status。失败置回 null（不显示继续支付按钮）：读不到服务端结论时宁可少给入口。 */
async function refreshPayStatus(orderNo: string) {
  try {
    payStatus.value = await rechargeApi.getPayStatus(orderNo)
  }
  catch {
    payStatus.value = null
  }
}

/**
 * 继续支付：复用充值页同一条「确认 → 模拟支付 → 轮询到终态」实现；
 * 完成后重拉详情与 pay-status，到账结论只来自服务端状态码，不以点击成功为准。
 */
async function handleContinuePay() {
  const order = detail.value?.order
  if (paying.value || !order || !continuePay.value.visible) {
    return
  }
  paying.value = true
  try {
    const settled = await payAndSettle(order.orderNo, order.orderAmountFen, {
      confirm: msg => message.confirm({ title: '确认支付', msg }).then(() => true).catch(() => false),
      notify: (kind, msg) => (kind === 'success' ? toast.success(msg) : toast.show(msg)),
      completedMessage: isPurchaseOrder.value ? '新卡已开通，购卡权益已到账' : '充值已到账',
    })
    if (settled !== null) {
      await load(order.orderNo)
    }
  }
  catch (error) {
    toast.error(error instanceof ContractError ? error.message : '支付发起失败，请重试')
  }
  finally {
    paying.value = false
  }
}

function copyOrderNo() {
  const orderNo = detail.value?.order.orderNo
  if (!orderNo) {
    return
  }
  uni.setClipboardData({
    data: orderNo,
    success: () => toast.show('订单号已复制'),
  })
}

function goAppeal() {
  if (detail.value && task.value) {
    goTo('U09', { orderNo: detail.value.order.orderNo, taskNo: task.value.taskNo })
  }
}

/**
 * 待接单取消：确认 → 服务端事务裁决 → 原样展示服务端结论文案 → 重新拉详情。
 * 结论文案不归并：资金段仍在途时把「退款处理中」显示成"已退款"就是谎报到账。
 */
async function handleCancelOrder() {
  const order = detail.value?.order
  if (cancelling.value || !order || !showCancelEntry.value) {
    return
  }
  const confirmed = await message
    .confirm({
      title: '取消配送订单',
      msg: '取消后本单不再配送，且无法恢复。确认取消？',
    })
    .then(() => true)
    .catch(() => false)
  if (!confirmed) {
    return
  }
  cancelling.value = true
  try {
    const resultText = await afterSaleApi.cancelPendingDeliveryOrder(order.orderNo)
    await message.alert({ title: '取消结果', msg: resultText }).catch(() => undefined)
    await load(order.orderNo)
  }
  catch (error) {
    toast.error(error instanceof ContractError ? error.message : '取消失败，请刷新后重试')
  }
  finally {
    cancelling.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="订单详情" back-to="U02" />
    <wd-toast />
    <wd-message-box />

    <view v-if="pageState === 'loading'" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, 1, { width: '70%' }]" />
    </view>

    <view v-else-if="pageState === 'error'" class="page-section">
      <AppPageState :state="errorImage === 'content' ? 'empty' : 'error'" :message="errorMessage">
        <template #actions>
          <wd-button plain @click="backOr('U02')">
            返回订单列表
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else-if="detail">
      <view class="page-section">
        <wd-card custom-class="block-card">
          <template #title>
            <view class="card-title-row">
              <view>订单信息</view>
              <view class="tag-row">
                <wd-tag plain>
                  {{ ORDER_TYPE_LABELS[detail.order.orderType] }}
                </wd-tag>
                <wd-tag :type="ORDER_STATUS_TONES[detail.order.orderStatus]" plain>
                  {{ ORDER_STATUS_LABELS[detail.order.orderStatus] }}
                </wd-tag>
              </view>
            </view>
          </template>
          <wd-cell-group>
            <wd-cell title="订单号" :label="detail.order.orderNo" clickable @click="copyOrderNo">
              <view class="copy-action">
                复制
              </view>
            </wd-cell>
            <wd-cell title="金额" :value="formatFen(detail.order.orderAmountFen)" />
            <wd-cell title="支付方式" :value="paymentMethodText" />
            <wd-cell
              v-if="detail.order.stationName"
              title="站点"
              :value="detail.order.stationName"
              ellipsis
            />
            <wd-cell
              v-if="detail.order.deviceNo"
              title="设备"
              :value="detail.order.deviceNo"
              ellipsis
            />
            <wd-cell title="创建时间" :value="formatBizTime(detail.order.createTime)" vertical />
            <wd-cell title="完成时间" :value="formatBizTime(detail.order.finishTime)" vertical />
          </wd-cell-group>
          <view class="e2e-order-evidence" aria-hidden="true">
            ORDER_STATUS={{ detail.order.orderStatus }};ACTUAL_ML={{ detail.order.actualMl ?? 'null' }}
          </view>
        </wd-card>
      </view>

      <view v-if="detail.trace.length" class="page-section">
        <wd-card custom-class="block-card" title="订单轨迹">
          <wd-steps :active="detail.trace.length - 1" vertical>
            <wd-step
              v-for="(node, index) in detail.trace"
              :key="`${node.node}-${index}`"
              :title="node.label"
              :status="stepStatus(node)"
            >
              <template #description>
                <view class="step-desc">
                  <view>{{ formatBizTime(node.time) }}</view>
                  <view v-if="node.detail" class="muted-text">
                    {{ node.detail }}
                  </view>
                </view>
              </template>
            </wd-step>
          </wd-steps>
        </wd-card>
      </view>

      <view v-if="detail.order.orderType === 1" class="page-section">
        <wd-card custom-class="block-card">
          <template #title>
            <view class="card-title-row">
              <view>取水命令</view>
              <wd-tag v-if="focusBlock === 'command'" type="primary" plain>
                当前关注
              </wd-tag>
            </view>
          </template>
          <wd-cell-group>
            <wd-cell title="命令号" :value="detail.commandNo ?? '—'" />
            <wd-cell
              title="命令状态"
              :value="detail.commandStatus !== undefined ? (COMMAND_STATUS_LABELS[detail.commandStatus] ?? '—') : '—'"
            />
            <wd-cell
              title="计划水量"
              :value="detail.order.planMl !== undefined ? formatMl(detail.order.planMl) : '—'"
            />
            <wd-cell
              title="实际水量"
              :value="detail.order.actualMl !== undefined ? formatMl(detail.order.actualMl) : '待设备回传'"
            />
            <wd-cell title="流水记录" :value="flowCountText" />
          </wd-cell-group>
        </wd-card>
      </view>

      <view v-if="detail.order.orderType === 2" class="page-section">
        <wd-card title="充值信息" custom-class="block-card">
          <wd-cell-group>
            <template v-if="rechargeRows === null">
              <wd-cell title="充值信息" label="本单数据异常，无法核对权益，请联系客服" />
            </template>
            <template v-else>
              <wd-cell
                v-for="row in rechargeRows"
                :key="row.label"
                :title="row.label"
                :value="row.value"
              />
            </template>
          </wd-cell-group>
          <view v-if="rechargeNoticeState" class="notice-wrap">
            <wd-notice-bar
              :type="rechargeNoticeState.tone"
              prefix="warn-bold"
              wrapable
              :scrollable="false"
              :text="rechargeNoticeState.text"
            />
            <!-- 客服入口紧跟提示条，仅 danger 提示才给（info 类不需要找人） -->
            <view v-if="rechargeNoticeState.tone === 'danger'" class="notice-contact">
              <WechatContactEntry
                scene="订单异常"
                :biz-no="detail?.order.orderNo"
                page-path="/pages/user/order/detail"
              />
            </view>
          </view>
          <view v-if="continuePay.visible" class="continue-pay">
            <wd-button block :loading="paying" @click="handleContinuePay">
              继续支付
            </wd-button>
          </view>
          <view v-else-if="continuePay.reason" class="muted-text boundary-note">
            {{ continuePay.reason }}
          </view>
        </wd-card>
      </view>

      <view v-if="isCompletedPurchase" class="page-section">
        <wd-card title="新卡信息" custom-class="block-card">
          <AppPageState v-if="issuedCardError" state="error" :message="issuedCardError" />
          <wd-cell-group v-else-if="issuedCardRows">
            <wd-cell
              v-for="row in issuedCardRows"
              :key="row.label"
              :title="row.label"
              :value="row.value"
            />
          </wd-cell-group>
          <view v-else class="muted-text">
            正在核对新卡发放结果…
          </view>
        </wd-card>
      </view>

      <view v-if="detail.order.orderType === 3" class="page-section">
        <wd-card custom-class="block-card">
          <template #title>
            <view class="card-title-row">
              <view>配送任务</view>
              <view class="tag-row">
                <!-- 补送标识（E2E-04 包C）：只在服务端标明补送任务时出现 -->
                <wd-tag v-if="isResendDelivery" type="primary" plain>
                  补送
                </wd-tag>
                <wd-tag v-if="focusBlock === 'delivery'" type="primary" plain>
                  当前关注
                </wd-tag>
                <wd-tag v-if="task" :type="TASK_STATUS_TONES[task.taskStatus]" plain>
                  {{ TASK_STATUS_LABELS[task.taskStatus] }}
                </wd-tag>
              </view>
            </view>
          </template>
          <template v-if="task">
            <wd-steps :active="deliveryActive" vertical>
              <wd-step
                v-for="node in deliveryTimeline"
                :key="node.key"
                :title="node.label"
                :description="node.time ? formatBizTime(node.time) : '未开始'"
              />
            </wd-steps>
            <wd-cell-group>
              <wd-cell title="任务号" :value="task.taskNo" />
              <wd-cell title="水种" :value="task.waterTypeName" />
              <wd-cell title="容器 × 数量" :value="`${task.containerSpec} × ${task.plannedDeliveryCount}`" />
              <wd-cell
                title="实际配送"
                :value="task.actualDeliveryCount !== undefined ? `${task.actualDeliveryCount} 件` : '待签收确认'"
              />
              <wd-cell title="预计回收" :value="`${task.plannedReturnCount} 件`" />
              <wd-cell
                title="实际回收"
                :value="task.actualReturnCount !== undefined ? `${task.actualReturnCount} 件` : '待签收确认'"
              />
              <wd-cell title="收货地址" :label="task.receiveAddress" vertical />
              <wd-cell title="联系电话" :value="task.maskedPhone" />
            </wd-cell-group>
            <!-- D-214 展示分流：payWay=3 呈现「水量抵扣 X L + 配送费」，不把 0 元水费渲染成免费 -->
            <view class="price-rows">
              <view
                v-for="line in deliveryPriceLines(task)"
                :key="line.label"
                class="price-row"
                :class="{ 'price-total': line.total }"
              >
                <view>{{ line.label }}</view>
                <view>{{ line.value }}</view>
              </view>
            </view>
            <view class="trace-title">
              签收三照
            </view>
            <view v-if="task.signPhotos.length" class="photo-grid">
              <view v-for="photo in task.signPhotos" :key="photo.type" class="photo-card">
                <wd-icon name="picture" size="28px" color="var(--app-text-disabled)" />
                <view class="photo-label">
                  {{ photo.label }}
                </view>
                <view class="muted-text">
                  {{ formatBizTimeShort(photo.time) }}
                </view>
              </view>
            </view>
            <view v-else class="muted-text">
              尚未签收，暂无三照记录。
            </view>
            <view v-if="canCreateDeliveryAppeal(task)" class="appeal-entry">
              <view class="muted-text">
                申诉截止 {{ appealDeadlineText }}
              </view>
              <wd-button block plain type="error" @click="goAppeal">
                发起申诉
              </wd-button>
            </view>
          </template>
          <view v-else class="muted-text">
            未查询到关联配送任务记录。
          </view>
        </wd-card>
      </view>

      <!--
        售后与退款（E2E-04 包E）：**只读**。
        小程序不提供退款发起、Refund-Sim 回放、补送生成或返还执行的任何入口——
        这些动作全部在管理端且需 order:aftersale:handle 权限。
      -->
      <view v-if="showAfterSaleBlock" class="page-section">
        <wd-card custom-class="block-card">
          <template #title>
            <view class="card-title-row">
              <view>售后与退款</view>
              <view class="tag-row">
                <wd-tag v-if="isResendOrder" type="primary" plain>
                  补送单
                </wd-tag>
                <wd-tag v-if="afterSale && afterSaleResult" :type="afterSaleResult.tone" plain>
                  {{ AFTER_SALE_STATUS_LABELS[afterSale.actionStatus] }}
                </wd-tag>
              </view>
            </view>
          </template>
          <template v-if="afterSale">
            <wd-cell-group>
              <wd-cell title="售后号" :value="afterSale.afterSaleNo" />
              <wd-cell title="售后来源" :value="AFTER_SALE_SOURCE_LABELS[afterSale.sourceType]" />
              <wd-cell title="处理方式" :value="AFTER_SALE_ACTION_TYPE_LABELS[afterSale.actionType]" />
              <wd-cell
                v-if="afterSale.approvedCount !== undefined"
                title="受影响数量"
                :value="`${afterSale.approvedCount} 件`"
              />
              <!-- 金额/水量逐项取服务端原值，页面不求和、不折算（合计亦为服务端字段） -->
              <wd-cell
                v-for="row in afterSaleRows"
                :key="row.label"
                :title="row.label"
                :value="row.value"
              />
              <!-- R0-8：退款来源照实展示，当前机构退款链路为 Refund-Sim，不写成微信退款 -->
              <wd-cell v-if="afterSaleRefundSource" title="退款来源" :value="afterSaleRefundSource" />
              <wd-cell v-if="afterSale.resendOrderNo" title="补送单号" :value="afterSale.resendOrderNo" />
              <wd-cell v-if="afterSale.resendTaskNo" title="补送任务号" :value="afterSale.resendTaskNo" />
              <wd-cell title="完成时间" :value="formatBizTime(afterSale.finishTime)" />
            </wd-cell-group>
            <view v-if="afterSaleResult" class="muted-text boundary-note">
              {{ afterSaleResult.text }}
            </view>
          </template>
          <view v-else class="muted-text">
            暂无售后记录；如需核对退款进度请联系客服。
          </view>

          <!-- 取消入口：服务端下发资格才出现（当前未下发即整块不显示） -->
          <view v-if="showCancelEntry" class="appeal-entry">
            <wd-button block plain type="error" :loading="cancelling" @click="handleCancelOrder">
              取消配送订单
            </wd-button>
          </view>
          <view v-else-if="cancelEligibility?.reason" class="muted-text boundary-note">
            {{ cancelEligibility.reason }}
          </view>
        </wd-card>
      </view>

      <view v-if="showAppealBlock" class="page-section">
        <wd-card custom-class="block-card">
          <template #title>
            <view class="card-title-row">
              <view>申诉记录</view>
              <view class="tag-row">
                <wd-tag v-if="focusBlock === 'appeal'" type="primary" plain>
                  当前关注
                </wd-tag>
                <wd-tag v-if="appeal" :type="APPEAL_STATUS_TONES[appeal.appealStatus]" plain>
                  {{ APPEAL_STATUS_LABELS[appeal.appealStatus] }}
                </wd-tag>
              </view>
            </view>
          </template>
          <template v-if="appeal">
            <wd-cell-group>
              <wd-cell title="申诉编号" :value="appeal.appealId" />
              <wd-cell title="原因" :value="APPEAL_REASON_LABELS[appeal.reason]" />
              <wd-cell title="说明" :label="appeal.description" />
              <wd-cell title="实收数量" :value="`${appeal.receivedCount} 件`" />
              <wd-cell title="用户凭证" :value="`${appeal.evidenceRefs.length} 件`" />
              <wd-cell title="登记时间" :value="formatBizTime(appeal.createTime)" />
              <wd-cell v-if="appeal.decisionSummary" title="裁决结果" :label="appeal.decisionSummary" />
            </wd-cell-group>
            <view class="trace-title">
              配送员举证
            </view>
            <view v-if="appeal.courierEvidences?.length" class="evidence-list">
              <view v-for="(item, index) in appeal.courierEvidences" :key="index" class="evidence-item">
                <view>{{ item.description }}</view>
                <view class="muted-text">
                  {{ formatBizTime(item.time) }} · 凭证 {{ item.evidenceRefs.length }} 件
                </view>
              </view>
            </view>
            <view v-else class="muted-text">
              配送员暂未追加举证。
            </view>
          </template>
          <view v-else class="muted-text">
            {{ appealError || '任务处于申诉中，申诉记录详情暂不可读。' }}
          </view>
        </wd-card>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.e2e-order-evidence {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
}

.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
}

.tag-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.copy-action {
  color: var(--wot-color-theme, var(--app-color-primary));
  font-size: 13px;
}

.boundary-note {
  margin-top: 8px;
  line-height: 1.6;
}

.trace-title {
  margin: 12px 0 8px;
  font-size: 14px;
  font-weight: 600;
}

.step-desc {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}

.continue-pay {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
  line-height: 1.6;
}

.notice-wrap {
  margin-top: 12px;
  border-radius: 8px;
  overflow: hidden;
}

.notice-contact {
  padding: 8px 0 0;
  text-align: right;
}

.price-rows {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--tint-primary);
}

.price-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 3px 0;
  font-size: 14px;
}

.price-total {
  font-weight: 600;
}

.photo-grid {
  display: flex;
  gap: 10px;
}

.photo-card {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 12px 4px;
  border: 1px dashed var(--line-2);
  border-radius: 8px;
}

.photo-label {
  font-size: 14px;
  font-weight: 600;
}

.appeal-entry {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.evidence-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.evidence-item {
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--tint-neutral);
  font-size: 13px;
}
</style>
