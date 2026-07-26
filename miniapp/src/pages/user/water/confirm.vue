<script setup lang="ts">
import type { CardSummary, UsableCard } from '@/api/card'
import type { DeviceOnlineStatus, DeviceRunStatus, WaterDeviceContext, WaterEligibility } from '@/api/device'
import type { PayWay } from '@/api/order'
import type { TagTone } from '@/utils/format'
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { cardApi, resolveCardSelection } from '@/api/card'
import { ContractError } from '@/api/common'
import { deviceApi } from '@/api/device'
import { orderApi } from '@/api/order'
import { currentMode } from '@/api/runtime'
import AppNavbar from '@/components/app-navbar.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import {
  AVAILABILITY_LABELS,
  CARD_BLOCK_LABELS,
  CARD_STATUS_LABELS,
  CARD_STATUS_TONES,
  formatBizTime,
  formatFen,
  formatMl,
  PAY_WAY_LABELS,
} from '@/utils/format'
import { backOr, redirectTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '取水确认',
  },
})

const toast = useToast()
const waterAdapterModes = [currentMode('device'), currentMode('order'), currentMode('card')]
const isRealWaterFlow = waterAdapterModes.every(mode => mode === 'real')
const isMockWaterFlow = waterAdapterModes.every(mode => mode === 'mock')
const waterBoundaryText = isRealWaterFlow
  ? '确认后将按所选水量从水卡真实扣款并下发取水指令，请核对设备、水种与金额。'
  : isMockWaterFlow
    ? '当前为全域 Mock 取水原型：确认后只生成本地订单与轨迹，不扣真实水卡、不下发真实设备指令。'
    : '当前取水链适配器未完整接真，不能作为真实扣款与设备下发验收；请先统一各域模式。'

/** 设备在线/运行状态中文口径（utils/format 暂无该映射，页面内先行冻结）。 */
const ONLINE_STATUS_LABELS: Record<DeviceOnlineStatus, string> = {
  ONLINE: '在线',
  OFFLINE: '离线',
}

const RUN_STATUS_LABELS: Record<DeviceRunStatus, string> = {
  IDLE: '空闲',
  DISPENSING: '出水中',
  FAULT: '故障',
  MAINTENANCE: '维护中',
  LOCKED: '已锁定',
}

const RUN_STATUS_TONES: Record<DeviceRunStatus, TagTone> = {
  IDLE: 'success',
  DISPENSING: 'primary',
  FAULT: 'danger',
  MAINTENANCE: 'warning',
  LOCKED: 'warning',
}

const PRESET_LITERS = [5, 10, 20]

const pageState = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
const errorImage = ref<'content' | 'network'>('network')

const scanSessionId = ref('')
const context = ref<WaterDeviceContext | null>(null)
const eligibility = ref<WaterEligibility | null>(null)
const card = ref<CardSummary | null>(null)
const usableCards = ref<UsableCard[]>([])
const switching = ref(false)

const planLiters = ref(10)
const payWay = ref<PayWay>(3)
const submitting = ref(false)

/** wd-input-number change：自定义水量按 1L 步长归一，避免小数/空值进入契约。 */
function onLitersChange({ value }: { value: number | string }) {
  planLiters.value = Math.max(1, Math.floor(Number(value) || 0))
}

/** 支付方式受控更新：仅接受水卡水量(3)/水卡余额(2)，微信支付(1)禁用不可选。 */
function onPayWayChange({ value }: { value: number | string | boolean }) {
  if (value === 2 || value === 3) {
    payWay.value = value
  }
}

const planMl = computed(() => Math.max(0, Math.floor(Number(planLiters.value) || 0) * 1000))

const estimatedAmountFen = computed(() => {
  if (!context.value || planMl.value <= 0) {
    return 0
  }
  return Math.ceil(planMl.value / 1000) * context.value.outlet.unitPriceFenPerLiter
})

/** 水量支付参考上限（=指定卡剩余水量）：只约束水量支付，余额支付不受它阻断（CARD-SCOPE）。 */
const maxAllowedMl = computed(() => eligibility.value?.maxAllowedMl)

/** 成员日剩余额度：仅成员取水时返回（CARD-MEMBER），有值才参与阻断，与水量余量互不混用。 */
const remainingDailyLimitMl = computed(() => eligibility.value?.remainingDailyLimitMl)

/** 设备侧阻断（蓝图 §9.4）：availability ≠ AVAILABLE 一律原位阻断并禁用主按钮。 */
const availabilityNotice = computed(() => {
  const value = eligibility.value
  if (!value || value.availability === 'AVAILABLE') {
    return ''
  }
  const label = AVAILABILITY_LABELS[value.availability]
  return value.reason ? `${label}：${value.reason}` : label
})

/** 卡/权益侧阻断，与设备侧分组展示（两组均通过才可下单）。 */
const cardBlockNotice = computed(() => {
  const block = eligibility.value?.cardBlock
  if (!block) {
    return ''
  }
  return `${CARD_BLOCK_LABELS[block.code]}：${block.message}`
})

/**
 * 表单内联错误（CARD-SCOPE 支付维度隔离）：水量支付只比 balanceMl vs planMl；
 * 余额支付只比 balanceFen vs estimatedAmountFen——余额足够但 balanceMl=0 时余额支付必须放行，
 * 不得再用 maxAllowedMl 对两种支付方式一刀切。remainingDailyLimitMl 只表成员日额度（有值才阻断）。
 */
const formError = computed(() => {
  if (!context.value) {
    return ''
  }
  if (planMl.value <= 0) {
    return '取水量必须大于 0'
  }
  if (remainingDailyLimitMl.value !== undefined && planMl.value > remainingDailyLimitMl.value) {
    return `超出成员今日剩余额度 ${formatMl(remainingDailyLimitMl.value)}`
  }
  if (payWay.value === 1) {
    return '微信支付待接入，请选择水卡支付方式'
  }
  if (card.value && payWay.value === 3 && card.value.balanceMl < planMl.value) {
    return `水卡剩余水量不足（剩余 ${formatMl(card.value.balanceMl)}）`
  }
  if (card.value && payWay.value === 2 && card.value.balanceFen < estimatedAmountFen.value) {
    return `水卡余额不足（余额 ${formatFen(card.value.balanceFen)}）`
  }
  return ''
})

const canSubmit = computed(() =>
  pageState.value === 'ready'
  && !!context.value
  && !!card.value
  && !availabilityNotice.value
  && !cardBlockNotice.value
  && !formError.value,
)

onLoad((query?: Record<string, string | undefined>) => {
  const sessionId = query?.scanSessionId
  if (!sessionId) {
    pageState.value = 'error'
    errorImage.value = 'content'
    errorMessage.value = '缺少扫码会话参数，请从首页重新扫码进入'
    return
  }
  scanSessionId.value = sessionId
  void loadAll()
})

async function loadAll() {
  pageState.value = 'loading'
  try {
    const [deviceContext, cards] = await Promise.all([
      deviceApi.getWaterDeviceContext(scanSessionId.value),
      cardApi.listUsableCards(),
    ])
    context.value = deviceContext
    usableCards.value = cards
    // CARD-MEMBER：多卡必须用户显式选择——静默选第一张会让成员在不知情时扣卡主的卡
    const selection = resolveCardSelection(cards)
    if (selection.mode === 'auto') {
      await applyCard(selection.selected.cardId)
    }
    else if (selection.mode === 'none') {
      // 无任何可用卡：仍走预检拿 CARD_MISSING 阻断，文案由服务端口径统一
      const precheck = await deviceApi.checkWaterEligibility(scanSessionId.value, undefined)
      eligibility.value = precheck
      card.value = null
    }
    // mode==='choose'：等用户点选，页面显示卡选择列表，不做任何预检
    pageState.value = 'ready'
  }
  catch (error) {
    pageState.value = 'error'
    errorImage.value = error instanceof ContractError && error.code === 'SCAN_SESSION_EXPIRED'
      ? 'content'
      : 'network'
    errorMessage.value = error instanceof ContractError ? error.message : '设备与权益预检加载失败，请重试'
  }
}

/** 选卡（含切卡）：CARD-SCOPE 契约「预检卡=下单卡」，换卡必须重新预检，绝不复用旧卡的预检结果。 */
async function applyCard(cardId: string) {
  switching.value = true
  try {
    const precheck = await deviceApi.checkWaterEligibility(scanSessionId.value, cardId)
    card.value = usableCards.value.find(item => item.cardId === cardId) ?? null
    eligibility.value = precheck
  }
  finally {
    switching.value = false
  }
}

/** 主操作：创建原型取水订单后 redirect 进 U05，避免返回后复用已消费会话。 */
async function handleSubmit() {
  if (!canSubmit.value || submitting.value || !context.value || !card.value) {
    return
  }
  const way = payWay.value
  if (way !== 2 && way !== 3) {
    return
  }
  submitting.value = true
  try {
    const detail = await orderApi.createWaterOrder({
      scanSessionId: scanSessionId.value,
      cardId: card.value.cardId,
      waterTypeId: context.value.outlet.waterTypeId,
      planMl: planMl.value,
      payWay: way,
    })
    redirectTo('U05', { orderNo: detail.order.orderNo })
  }
  catch (error) {
    toast.show(error instanceof ContractError ? error.message : '取水下单失败，请重试')
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="取水确认" back-to="U01" />
    <wd-toast />
    <AppPrototypeNotice :text="waterBoundaryText" />

    <view v-if="pageState === 'loading'" class="page-section loading-box">
      <wd-loading />
      <view class="muted-text">
        正在加载设备与权益预检…
      </view>
    </view>

    <view v-else-if="pageState === 'error'" class="page-section">
      <wd-status-tip :image="errorImage" :tip="errorMessage">
        <template #bottom>
          <view class="status-actions">
            <wd-button plain @click="backOr('U01')">
              返回首页
            </wd-button>
          </view>
        </template>
      </wd-status-tip>
    </view>

    <template v-else-if="context && eligibility">
      <view class="page-section">
        <wd-card custom-class="block-card">
          <template #title>
            <view class="card-title-row">
              <view class="card-title-text">
                {{ context.stationName }} · {{ context.deviceNo }}
              </view>
              <view class="tag-row">
                <wd-tag :type="context.onlineStatus === 'ONLINE' ? 'success' : 'danger'" plain>
                  {{ ONLINE_STATUS_LABELS[context.onlineStatus] }}
                </wd-tag>
                <wd-tag :type="RUN_STATUS_TONES[context.runStatus]" plain>
                  {{ RUN_STATUS_LABELS[context.runStatus] }}
                </wd-tag>
              </view>
            </view>
          </template>
          <wd-cell-group>
            <wd-cell title="设备名称" :value="context.deviceName" />
            <wd-cell title="出水口 / 水种" :value="`${context.outlet.outletNo} 号口 · ${context.outlet.waterTypeName}`" />
            <wd-cell title="单价" :value="`${formatFen(context.outlet.unitPriceFenPerLiter)}/升`" />
            <wd-cell title="价格快照" :value="`会话有效期至 ${formatBizTime(context.expiresAt)}`" />
          </wd-cell-group>
        </wd-card>
      </view>

      <view v-if="availabilityNotice" class="page-section">
        <wd-notice-bar type="danger" prefix="warn-bold" wrapable :scrollable="false" :text="availabilityNotice" />
      </view>
      <view v-if="cardBlockNotice" class="page-section">
        <wd-notice-bar type="danger" prefix="warn-bold" wrapable :scrollable="false" :text="cardBlockNotice" />
      </view>

      <view class="page-section">
        <wd-card title="取水量" custom-class="block-card">
          <view class="preset-row">
            <wd-button
              v-for="preset in PRESET_LITERS"
              :key="preset"
              size="small"
              :plain="planLiters !== preset"
              @click="planLiters = preset"
            >
              {{ preset }}L
            </wd-button>
          </view>
          <view class="volume-row">
            <view class="muted-text">
              自定义水量（升，步长 1L）
            </view>
            <wd-input-number :model-value="planLiters" :min="1" :step="1" input-width="56px" @change="onLitersChange" />
          </view>
          <view class="estimate-row">
            <view>预计金额</view>
            <view class="estimate-value">
              {{ formatFen(estimatedAmountFen) }}
            </view>
          </view>
          <view class="muted-text">
            按 {{ formatFen(context.outlet.unitPriceFenPerLiter) }}/升 × {{ formatMl(planMl) }} 计算，实际以设备出水结果结算。
          </view>
          <view v-if="formError" class="form-error">
            {{ formError }}
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card title="支付方式" custom-class="block-card">
          <wd-radio-group :model-value="payWay" cell @change="onPayWayChange">
            <wd-radio :value="3">
              {{ PAY_WAY_LABELS[3] }}（剩余 {{ card ? formatMl(card.balanceMl) : '—' }}）
            </wd-radio>
            <wd-radio :value="2">
              {{ PAY_WAY_LABELS[2] }}（余额 {{ card ? formatFen(card.balanceFen) : '—' }}）
            </wd-radio>
            <wd-radio :value="1" disabled>
              {{ PAY_WAY_LABELS[1] }}
            </wd-radio>
          </wd-radio-group>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card custom-class="block-card">
          <template #title>
            <view class="card-title-row">
              <view>水卡权益</view>
              <wd-tag v-if="card" :type="CARD_STATUS_TONES[card.cardStatus]" plain>
                {{ CARD_STATUS_LABELS[card.cardStatus] }}
              </wd-tag>
            </view>
          </template>
          <!-- CARD-MEMBER：多张可用卡必须显式选择；成员卡标注归属，选卡即以该卡重新预检 -->
          <wd-cell-group v-if="usableCards.length > 1" border custom-class="card-picker">
            <wd-cell
              v-for="item in usableCards"
              :key="item.cardId"
              :title="item.cardNo"
              :label="item.accessRole === 'MEMBER' ? '成员授权卡（扣持卡人余额）' : '本人水卡'"
              clickable
              @click="!switching && applyCard(item.cardId)"
            >
              <wd-icon v-if="card?.cardId === item.cardId" name="check-outline" size="18px" />
            </wd-cell>
          </wd-cell-group>
          <view v-if="usableCards.length > 1 && !card" class="muted-text">
            存在多张可用水卡，请先选择本次取水使用的卡。
          </view>
          <wd-cell-group v-if="card">
            <wd-cell title="卡号" :value="card.cardNo" />
            <wd-cell title="余额" :value="formatFen(card.balanceFen)" />
            <wd-cell title="剩余水量" :value="formatMl(card.balanceMl)" />
            <wd-cell
              title="水量支付可用"
              :value="maxAllowedMl !== undefined ? formatMl(maxAllowedMl) : '未返回'"
            />
            <wd-cell
              v-if="remainingDailyLimitMl !== undefined"
              title="今日剩余额度"
              :value="formatMl(remainingDailyLimitMl)"
            />
          </wd-cell-group>
          <view v-else class="muted-text">
            当前账号暂无可用水卡，无法完成取水下单。
          </view>
        </wd-card>
      </view>

      <view class="page-section confirm-footer">
        <view class="muted-text footer-note">
          {{ isRealWaterFlow ? '确认后将从水卡真实扣款并通知设备出水' : '确认后按当前原型适配器生成订单' }}
        </view>
        <wd-button block size="large" :disabled="!canSubmit" :loading="submitting" @click="handleSubmit">
          确认并开始取水
        </wd-button>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.loading-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 64px 0;
}

.status-actions {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}

.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
}

.card-title-text {
  flex: 1;
  min-width: 0;
}

.tag-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.preset-row {
  display: flex;
  gap: 10px;
  padding-bottom: 12px;
}

.volume-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0 12px;
}

.estimate-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 0 4px;
  font-size: 15px;
}

.estimate-value {
  color: var(--wot-color-theme, var(--app-color-primary));
  font-size: 20px;
  font-weight: 600;
}

.form-error {
  margin-top: 8px;
  color: #fa4350;
  font-size: 13px;
}

.confirm-footer {
  margin-top: 20px;
}

.footer-note {
  margin-bottom: 8px;
  text-align: center;
}
</style>
