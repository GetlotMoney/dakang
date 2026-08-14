<script setup lang="ts">
import type { MallAfterSaleType } from '@/api/mall-aftersale'
import type { MallOrderDetail } from '@/api/mall-trade'
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { ContractError } from '@/api/common'
import { MALL_AFTER_SALE_REASON_MAX, mallAfterSaleApi } from '@/api/mall-aftersale'
import { createMallRequestId, mallTradeApi } from '@/api/mall-trade'
import AppBottomActionBar from '@/components/app-bottom-action-bar.vue'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import MallGoodsLine from '@/components/mall-goods-line.vue'
import { formatFen, formatSpecs } from '@/utils/format'
import { goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '申请售后',
  },
})

/**
 * 售后申请页。
 *
 * 页面只提交「哪几条明细、各几件、什么原因」；应退金额由服务端按原订单不可变明细算出。
 * 这里不显示预估退款额——预估值和实际到账不一致时，用户记住的是预估值。
 */

const toast = useToast()

/**
 * 可选类型随订单状态变化，与服务端准入判据同形：待履约（未开始拣货）只能整单取消退款，
 * 已完成才谈退货或换货；跨 SKU 换货与差价退款超出本期范围，不设入口。
 */
const ALL_TYPES: { value: MallAfterSaleType, label: string, hint: string }[] = [
  { value: 3, label: '取消订单退款', hint: '尚未拣货，整单取消并原路退款' },
  { value: 1, label: '退货退款', hint: '寄回商品，质检通过后原路退款' },
  { value: 2, label: '换货', hint: '同款同数量换新，不涉及差价' },
]

const loading = ref(true)
const errorMessage = ref('')
const submitting = ref(false)
const detail = ref<MallOrderDetail | null>(null)
const afterSaleType = ref<MallAfterSaleType | undefined>(undefined)
const applyReason = ref('')
/** 每条明细的申请数量，键为 orderItemId。 */
const quantities = ref<Record<string, number>>({})
/** 订单号进入模板（阻断态的返回入口），用 ref 保证渲染读到的是最新值。 */
const orderNo = ref('')

/** 可选类型随订单状态收敛（与服务端 requireApplicable 同形）；空数组=无售后入口，页面进入阻断态。 */
const typeOptions = computed(() => {
  const orderStatus = detail.value?.summary.orderStatus
  if (orderStatus === 2) {
    return ALL_TYPES.filter(option => option.value === 3)
  }
  if (orderStatus === 4) {
    return ALL_TYPES.filter(option => option.value !== 3)
  }
  return []
})

onLoad((query) => {
  orderNo.value = typeof query?.orderNo === 'string' ? query.orderNo : ''
  refresh()
})

async function refresh() {
  if (!orderNo.value) {
    loading.value = false
    errorMessage.value = '缺少订单参数'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const loaded = await mallTradeApi.orderDetail(orderNo.value)
    detail.value = loaded
    const init: Record<string, number> = {}
    for (const item of loaded.items) {
      init[item.orderItemId] = 0
    }
    quantities.value = init
    afterSaleType.value = typeOptions.value[0]?.value
  }
  catch (error) {
    detail.value = null
    errorMessage.value = error instanceof ContractError ? error.message : '订单加载失败，请重试'
  }
  finally {
    loading.value = false
  }
}

/** 整单取消不选行：范围恒为整单，由服务端按订单明细全量展开。 */
const wholeOrder = computed(() => afterSaleType.value === 3)

/** 已选行；只报数量，金额留给服务端。 */
const selectedLines = computed(() =>
  Object.entries(quantities.value)
    .filter(([, quantity]) => quantity > 0)
    .map(([orderItemId, quantity]) => ({ orderItemId, quantity })),
)

const canSubmit = computed(() =>
  afterSaleType.value !== undefined
  && applyReason.value.trim().length > 0
  && (wholeOrder.value || selectedLines.value.length > 0),
)

/** 数量只在 [0, 已购数量] 内取整；越界一律夹回，不把非法值传给服务端再换一句报错。 */
function setQuantity(orderItemId: string, raw: unknown, max: number) {
  const value = Math.trunc(Number(raw))
  if (!Number.isFinite(value)) {
    return
  }
  quantities.value = {
    ...quantities.value,
    [orderItemId]: Math.min(Math.max(value, 0), max),
  }
}

async function handleSubmit() {
  const chosenType = afterSaleType.value
  if (submitting.value || !canSubmit.value || chosenType === undefined) {
    return
  }
  submitting.value = true
  try {
    const created = await mallAfterSaleApi.apply({
      requestId: createMallRequestId(),
      orderNo: orderNo.value,
      afterSaleType: chosenType,
      applyReason: applyReason.value.trim(),
      lines: wholeOrder.value ? undefined : selectedLines.value,
    })
    toast.success('已提交申请')
    goTo('M11', { afterSaleNo: created.afterSaleNo })
  }
  catch (error) {
    toast.show(error instanceof ContractError ? error.message : '提交失败，请重试')
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="page-shell page-shell--with-bar">
    <AppNavbar title="申请售后" back-to="M06" />
    <wd-toast />

    <view v-if="loading" class="page-section">
      <AppPageState state="loading" />
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain size="small" @click="refresh">
            重新加载
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <view v-else-if="!typeOptions.length" class="page-section">
      <AppPageState
        state="blocked"
        title="当前订单不可申请售后"
        message="只有已支付未拣货的订单可整单取消，签收完成的订单可申请退货或换货。"
      >
        <template #actions>
          <wd-button plain size="small" @click="goTo('M06', { orderNo })">
            返回订单详情
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else-if="detail">
      <view class="page-section info-card">
        <view class="info-title">
          售后类型
        </view>
        <view
          v-for="option in typeOptions"
          :key="option.value"
          class="type-option"
          :class="{ 'type-option--active': afterSaleType === option.value }"
          @click="afterSaleType = option.value"
        >
          <view class="type-option__head">
            <text class="type-option__label">
              {{ option.label }}
            </text>
            <wd-icon
              v-if="afterSaleType === option.value"
              name="check"
              size="16px"
              color="var(--app-color-primary)"
            />
          </view>
          <text class="muted-text">
            {{ option.hint }}
          </text>
        </view>
      </view>

      <view class="page-section info-card">
        <view class="info-title">
          {{ wholeOrder ? '取消范围（整单）' : '选择商品与数量' }}
        </view>
        <view v-for="item in detail.items" :key="item.orderItemId" class="apply-line">
          <MallGoodsLine
            :name="item.productName"
            :spec="formatSpecs(item.specs, item.skuName)"
            :price="formatFen(item.unitPriceFen)"
            :quantity="item.quantity"
          />
          <view v-if="!wholeOrder" class="apply-line__step">
            <text class="muted-text">
              申请数量
            </text>
            <wd-input-number
              :model-value="quantities[item.orderItemId] ?? 0"
              :min="0"
              :max="item.quantity"
              @change="event => setQuantity(item.orderItemId, event.value, item.quantity)"
            />
          </view>
        </view>
      </view>

      <view class="page-section info-card">
        <view class="info-title">
          申请原因
        </view>
        <wd-textarea
          v-model="applyReason"
          :maxlength="MALL_AFTER_SALE_REASON_MAX"
          show-word-limit
          placeholder="请说明遇到的问题，便于前置仓核实"
        />
      </view>

      <AppBottomActionBar>
        <template #summary>
          <text class="muted-text">
            {{ wholeOrder ? '范围：整单' : `已选 ${selectedLines.length} 项` }}
          </text>
        </template>
        <template #primary>
          <wd-button
            type="primary"
            :loading="submitting"
            :disabled="!canSubmit"
            @click="handleSubmit"
          >
            提交申请
          </wd-button>
        </template>
      </AppBottomActionBar>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.info-card {
  padding: 12px;
  background: var(--app-bg-card);
  border-radius: var(--r-md);
}

.info-title {
  margin-bottom: 8px;
  font-size: var(--fs-title);
  font-weight: 600;
}

.type-option {
  padding: 10px;
  margin-bottom: 8px;
  border: 1px solid var(--app-border);
  border-radius: var(--r-sm);

  &--active {
    border-color: var(--app-color-primary);
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__label {
    font-size: var(--fs-body);
    font-weight: 600;
  }
}

.apply-line {
  padding-bottom: 8px;
  border-bottom: 1px solid var(--app-border);

  &:last-child {
    border-bottom: none;
  }

  &__step {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-top: 4px;
  }
}
</style>
