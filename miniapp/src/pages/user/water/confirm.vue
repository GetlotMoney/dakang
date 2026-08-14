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
import AppBottomActionBar from '@/components/app-bottom-action-bar.vue'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
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
import { backOr, goTo, redirectTo } from '@/utils/navigation'
import {
  buildCreateWaterOrderPayload,
  canSubmitWater,
  estimatedAmountFen as computeAmountFen,
  isQuoteInvalidCode,
  resolveCardHint,
} from './quote'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '取水确认',
  },
})

const toast = useToast()

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
/** 报价失效/会话过期：主按钮永久禁用，必须重新扫码，不允许在失效页面反复点提交。 */
const quoteInvalid = ref(false)
const errorMessage = ref('')

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

// 公式唯一实现在 ./quote，与单测共用同一份——页面内联一份、测试再抄一份，改坏了两边都不会红。
const estimatedAmountFen = computed(() =>
  context.value ? computeAmountFen(planMl.value, context.value.outlet.unitPriceFenPerLiter) : 0,
)

/** 水量支付参考上限（=指定卡剩余水量）：只约束水量支付，余额支付不受它阻断（CARD-SCOPE）。 */
const maxAllowedMl = computed(() => eligibility.value?.maxAllowedMl)

/** 成员日剩余额度：仅成员取水时返回（CARD-MEMBER），有值才参与阻断，与水量余量互不混用。 */
const remainingDailyLimitMl = computed(() => eligibility.value?.remainingDailyLimitMl)

/** 设备侧阻断：availability ≠ AVAILABLE 一律原位阻断并禁用主按钮。 */
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
    return '微信支付暂不可用，请选择水卡支付方式'
  }
  if (card.value && payWay.value === 3 && card.value.balanceMl < planMl.value) {
    return `水卡剩余水量不足（剩余 ${formatMl(card.value.balanceMl)}）`
  }
  if (card.value && payWay.value === 2 && card.value.balanceFen < estimatedAmountFen.value) {
    return `水卡余额不足（余额 ${formatFen(card.value.balanceFen)}）`
  }
  return ''
})

// 判据唯一实现在 ./quote.canSubmitWater（与单测共用）。hasEligibility 不可省：
// 多卡场景下卡与预检是分两步就位的，缺这一项就可能在零预检状态下放行提交。
const canSubmit = computed(() => canSubmitWater({
  quoteInvalid: quoteInvalid.value,
  ready: pageState.value === 'ready',
  hasContext: !!context.value,
  hasCard: !!card.value,
  hasEligibility: !!eligibility.value,
  switchingCard: switching.value,
  availabilityNotice: availabilityNotice.value,
  cardBlockNotice: cardBlockNotice.value,
  formError: formError.value,
}))

/** 水卡区三态：none=一张都没有，choose=有多张但还没选，selected=已选定。 */
const cardHint = computed(() => resolveCardHint(usableCards.value.length, !!card.value))

onLoad((query?: Record<string, string | undefined>) => {
  const sessionId = query?.scanSessionId
  if (!sessionId) {
    pageState.value = 'error'
    errorMessage.value = '请从首页重新扫码进入'
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
    if (isQuoteInvalid(error)) {
      markQuoteInvalid(error)
      return
    }
    pageState.value = 'error'
    errorMessage.value = error instanceof ContractError ? error.message : '设备信息加载失败，请重试'
  }
}

/** 会话过期与报价变化都意味着本次扫码作废，处置动作相同：清上下文、禁提交、要求重扫。 */
function isQuoteInvalid(error: unknown): boolean {
  return error instanceof ContractError && isQuoteInvalidCode(error.code)
}

function markQuoteInvalid(error: unknown) {
  quoteInvalid.value = true
  submitting.value = false
  context.value = null
  eligibility.value = null
  pageState.value = 'error'
  errorMessage.value = error instanceof ContractError && error.code === 'SCAN_QUOTE_CHANGED'
    ? '报价已变化，请重新扫码'
    : '报价已变化或已超时，请重新扫码'
}

/**
 * 选卡核心：CARD-SCOPE「预检卡=下单卡」，换卡必须重新预检，绝不复用旧卡的预检结果。
 * 失败一律抛出不在这里吞：加载期失败要落错误页、手动切卡失败只需原位提示，两个调用方处置不同。
 * card 与 eligibility 只在预检成功后成对写入，绝不出现「卡换了、预检还是旧卡的」。
 */
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

/**
 * 用户点选/切卡：这是点击直接触发的，不在 loadAll 的 try 里，必须自己兜住异常，
 * 否则 5410/5411 会成为一个未处理的 Promise rejection——页面既不置报价失效、也不提示。
 * 非报价类失败保留上一张卡与它的预检结论，用户可以再点一次重试。
 */
async function pickCard(cardId: string) {
  try {
    await applyCard(cardId)
  }
  catch (error) {
    if (isQuoteInvalid(error)) {
      markQuoteInvalid(error)
      return
    }
    toast.show(error instanceof ContractError ? error.message : '水卡校验失败，请重试')
  }
}

/** 主操作：创建原型取水订单后 redirect 进 U05，避免返回后复用已消费会话。 */
async function handleSubmit() {
  // switching 双保险：canSubmit 已含它，这里再挡一次——切卡在途时 card 仍是上一张，
  // 放行就等于用户点了 B 却扣了 A
  if (!canSubmit.value || submitting.value || switching.value || !context.value || !card.value) {
    return
  }
  const way = payWay.value
  if (way !== 2 && way !== 3) {
    return
  }
  submitting.value = true
  try {
    // 入参白名单唯一实现在 ./quote.buildCreateWaterOrderPayload（价格/金额/设备共键一律不上报）
    const detail = await orderApi.createWaterOrder(buildCreateWaterOrderPayload({
      scanSessionId: scanSessionId.value,
      cardId: card.value.cardId,
      waterTypeId: context.value.outlet.waterTypeId,
      planMl: planMl.value,
      payWay: way,
    }))
    redirectTo('U05', { orderNo: detail.order.orderNo })
  }
  catch (error) {
    if (isQuoteInvalid(error)) {
      markQuoteInvalid(error)
      return
    }
    toast.show(error instanceof ContractError ? error.message : '取水下单失败，请重试')
  }
  finally {
    submitting.value = false
  }
}

/**
 * 吸底栏上展示的阻断原因：按「设备不可用 → 水卡受限 → 表单错误 → 切卡在途」取第一条。
 * 纯展示挑选，不参与 canSubmit——能不能提交仍只由 canSubmitWater 判定。
 */
const blockReason = computed(() => {
  if (availabilityNotice.value) {
    return availabilityNotice.value
  }
  if (cardBlockNotice.value) {
    return cardBlockNotice.value
  }
  if (formError.value) {
    return formError.value
  }
  return switching.value ? '正在切换水卡' : ''
})
</script>

<template>
  <view
    class="page-shell"
    :class="{ 'page-shell--with-bar': pageState === 'ready' && !!context }"
  >
    <AppNavbar title="取水确认" back-to="U01" />
    <wd-toast />

    <view v-if="pageState === 'loading'" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, 1, { width: '70%' }]" />
    </view>

    <view v-else-if="pageState === 'error'" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain @click="backOr('U01')">
            返回首页
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <!-- 门只看 context，绝不能再叠 eligibility：多卡用户选卡前 eligibility 恒 null 而选卡列表就在门内，
         叠上去页面自锁；预检结论缺失由 canSubmit 的 hasEligibility 兜住 -->
    <template v-else-if="context">
      <view class="page-section">
        <wd-card custom-class="block-card">
          <template #title>
            <view class="card-title-row">
              <view class="card-title-text">
                {{ context.stationName }}
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
            <wd-cell title="设备名称" :value="context.deviceName" ellipsis />
            <wd-cell title="设备编号" :value="context.deviceNo" ellipsis />
            <wd-cell title="出水口" :value="`${context.outlet.outletNo}号口`" />
            <wd-cell title="水种" :value="context.outlet.waterTypeName" ellipsis />
            <wd-cell title="单价" :value="`${formatFen(context.outlet.unitPriceFenPerLiter)}/升`" />
            <wd-cell title="报价有效至" :value="formatBizTime(context.expiresAt)" />
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
              自定义水量（升）
            </view>
            <wd-input-number :model-value="planLiters" :min="1" :step="1" input-width="56px" @change="onLitersChange" />
          </view>
          <view class="estimate-row">
            <view>预计金额</view>
            <view class="estimate-value">
              {{ formatFen(estimatedAmountFen) }}
            </view>
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
              @click="!switching && pickCard(item.cardId)"
            >
              <wd-icon v-if="card?.cardId === item.cardId" name="check-outline" size="18px" />
            </wd-cell>
          </wd-cell-group>
          <view v-if="cardHint === 'choose'" class="muted-text">
            请先选择本次取水使用的水卡。
          </view>
          <view v-if="switching" class="muted-text">
            正在切换水卡，请稍候…
          </view>
          <wd-cell-group v-if="card">
            <wd-cell title="卡号" :value="card.cardNo" />
            <wd-cell title="余额" :value="formatFen(card.balanceFen)" />
            <wd-cell title="剩余水量" :value="formatMl(card.balanceMl)" />
            <wd-cell
              title="水量支付可用"
              :value="maxAllowedMl !== undefined ? formatMl(maxAllowedMl) : '—'"
            />
            <wd-cell
              v-if="remainingDailyLimitMl !== undefined"
              title="今日剩余额度"
              :value="formatMl(remainingDailyLimitMl)"
            />
          </wd-cell-group>
          <!-- 只在真的一张卡都没有时才这么说：有卡未选是 choose 态，说成"暂无可用水卡"是误导 -->
          <view v-else-if="cardHint === 'none'" class="hint-with-action">
            <text class="muted-text">
              暂无可用水卡
            </text>
            <wd-button size="small" plain @click="goTo('U10')">
              去购卡
            </wd-button>
          </view>
          <!-- 有卡却没能选中（预检未通过）：不能说"暂无可用水卡"，也不能叫用户去点不存在的列表 -->
          <view v-else-if="cardHint === 'unresolved'" class="hint-with-action">
            <text class="muted-text">
              本次扫码不能下单
            </text>
            <wd-button size="small" plain @click="backOr('U01')">
              重新扫码
            </wd-button>
          </view>
        </wd-card>
      </view>

      <AppBottomActionBar>
        <!-- 阻断原因贴着主按钮，只显示当前生效的第一条；判据仍在 canSubmit -->
        <template #summary>
          <text class="muted-text">
            {{ blockReason || '预计金额' }}
          </text>
          <text v-if="!blockReason" class="money bar-amount">
            {{ formatFen(estimatedAmountFen) }}
          </text>
        </template>
        <template #primary>
          <wd-button
            size="large"
            type="primary"
            :disabled="!canSubmit"
            :loading="submitting || switching"
            @click="handleSubmit"
          >
            确认并开始取水
          </wd-button>
        </template>
      </AppBottomActionBar>
    </template>
  </view>
</template>

<style scoped lang="scss">
.hint-with-action {
  display: flex;
  gap: var(--sp-3);
  align-items: center;
  justify-content: space-between;
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
  color: var(--app-color-danger);
  font-size: 13px;
}

.bar-amount {
  font-size: var(--fs-title);
  font-weight: 600;
}
</style>
