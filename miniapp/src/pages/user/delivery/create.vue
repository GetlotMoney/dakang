<script setup lang="ts">
import type { CardSummary, DeliveryAddress } from '@/api/card'
import type { StationSummary, WaterType } from '@/api/catalog'
import type { CreateDeliveryOrderInput, DeliveryPayWay, DeliveryTask } from '@/api/delivery'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { computed, ref, watch } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { catalogApi } from '@/api/catalog'
import { ContractError } from '@/api/common'
import {
  CONTAINER_WATER_PRICE_FEN,
  DELIVERY_FEE_PER_CONTAINER_FEN,
  deliveryApi,
  deliveryPayWayOptions,
  deliveryPriceLines,
  deliveryWaterMl,
  newDeliveryRequestId,
} from '@/api/delivery'
import AppNavbar from '@/components/app-navbar.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import { consumeDeliveryDraft } from '@/store/delivery-draft'
import { formatFen, formatMl, PAY_WAY_LABELS } from '@/utils/format'
import { goTo, redirectTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '配送下单',
  },
})

type ContainerSpec = DeliveryTask['containerSpec']

const toast = useToast()
const message = useMessage()

/** 容器规格选项直接取自契约价目表键，保证费用预览与下单快照同源（REQ-060 / api/delivery.ts）。 */
const CONTAINER_SPECS = Object.keys(CONTAINER_WATER_PRICE_FEN) as ContainerSpec[]

/** 预约时间下限取打开页面时刻；最终以契约“晚于业务当前时间”校验为准。 */
const minScheduledDate = Date.now()

const loading = ref(true)
const submitting = ref(false)

const addresses = ref<DeliveryAddress[]>([])
const stations = ref<StationSummary[]>([])
const waterTypes = ref<WaterType[]>([])
const primaryCard = ref<CardSummary | null>(null)

const selectedAddressId = ref('')
const selectedStationId = ref('')
const waterTypeId = ref('')
const containerSpec = ref<ContainerSpec>('5L桶')
const deliveryCount = ref(1)
const plannedReturnCount = ref(0)
const deliveryMode = ref<CreateDeliveryOrderInput['deliveryMode']>('immediate')
const scheduledTimestamp = ref<number | string>('')
const autoRefillIntervalDays = ref(7)
/** 支付方式（D-214 二选一）：默认余额；水量抵扣需卡水量与配送费余额双满足。 */
const payWay = ref<DeliveryPayWay>(2)

// real 无地址簿域（包A 冻结为快照式输入）：收水地址与电话随单直填，服务端最终校验。
const receiveAddress = ref('')
const receivePhone = ref('')
/** 手动填写模式：默认走地址簿（addressId 服务端解引用）；无地址或用户主动切换时直填。 */
const manualAddress = ref(false)

/**
 * 幂等键在一次提交意图内保持不变：失败重试复用同一 requestId（服务端恒返回同单，
 * 不会双扣款）；成功后再换新键。Mock 忽略该字段。
 */
const requestId = ref(newDeliveryRequestId())

// 出货三份 env 的 delivery 恒为 real，「演示数据，不会真实扣款」这条回落分支不可达；
// 且它一旦可达就会对着一个真扣水卡余额的构建说不扣款，与 runtime-notice 拆除的
// mock 回落是同一类事故，故不保留分支，只留唯一成立的这句。
const noticeText = '提交后从水卡扣款，无法撤销。'

const selectedAddress = computed(() =>
  addresses.value.find(item => item.addressId === selectedAddressId.value),
)
const selectedStation = computed(() =>
  stations.value.find(item => item.id === selectedStationId.value),
)
const waterTypeColumns = computed(() =>
  waterTypes.value.map(item => ({ label: item.name, value: item.id })),
)

// 费用预览实时计算，复用契约导出的价目常量与水量换算表，不另抄数字（D-214）。
const waterAmountFen = computed(
  () => deliveryCount.value * CONTAINER_WATER_PRICE_FEN[containerSpec.value],
)
const deliveryFeeFen = computed(() => deliveryCount.value * DELIVERY_FEE_PER_CONTAINER_FEN)
const totalAmountFen = computed(() => waterAmountFen.value + deliveryFeeFen.value)
const waterMlPreview = computed(() => deliveryWaterMl(containerSpec.value, deliveryCount.value))

/**
 * 两支付选项的实时可用性（契约纯函数，拒因与服务端同文案）：
 * 全余额需 余额≥水费+配送费；水量抵扣需 卡水量≥抵扣量 且 余额≥配送费。
 * 自动补货只支持余额（服务端同边界：规则表无支付方式列）。
 */
const payOptions = computed(() => deliveryPayWayOptions(
  primaryCard.value
    ? { balanceFen: primaryCard.value.balanceFen, balanceMl: primaryCard.value.balanceMl }
    : null,
  {
    totalFen: totalAmountFen.value,
    deliveryFeeFen: deliveryFeeFen.value,
    waterMl: waterMlPreview.value,
  },
  { autoRefill: deliveryMode.value === 'auto-refill' },
))
const balanceOption = computed(() => payOptions.value.find(option => option.payWay === 2)!)
const mlOption = computed(() => payOptions.value.find(option => option.payWay === 3)!)

/** 选项说明行：可用时给余额/水量现状，禁用时给禁用原因（不足项文案与服务端拒因一致）。 */
const balanceOptionNote = computed(() => {
  if (balanceOption.value.disabled) {
    return balanceOption.value.reason ?? ''
  }
  return primaryCard.value
    ? `当前余额 ${formatFen(primaryCard.value.balanceFen)}，本单应扣 ${formatFen(totalAmountFen.value)}`
    : ''
})
const mlOptionNote = computed(() => {
  if (mlOption.value.disabled) {
    return mlOption.value.reason ?? ''
  }
  return primaryCard.value
    ? `当前水量 ${formatMl(primaryCard.value.balanceMl)}，本单抵扣 ${formatMl(waterMlPreview.value)}；`
    + `配送费 ${formatFen(deliveryFeeFen.value)} 从余额扣除`
    : ''
})

// 所选方式被数量/方式变化挤成不可用时回落默认余额，避免带着禁用项提交
watch([payOptions, payWay], () => {
  if (payWay.value === 3 && mlOption.value.disabled && !balanceOption.value.disabled) {
    payWay.value = 2
  }
})

/** 费用预览行与订单详情共用同一契约展示函数：payWay=3 呈现水量抵扣行而不是 0 元水费。 */
const priceLines = computed(() => deliveryPriceLines({
  payWay: payWay.value,
  containerSpec: containerSpec.value,
  plannedDeliveryCount: deliveryCount.value,
  priceSnapshot: {
    waterAmountFen: payWay.value === 3 ? 0 : waterAmountFen.value,
    deliveryFeeFen: deliveryFeeFen.value,
    totalAmountFen: payWay.value === 3 ? deliveryFeeFen.value : totalAmountFen.value,
  },
}))

onLoad((options?: Record<string, string>) => {
  const query = options ?? {}
  if (query.addressId) {
    selectedAddressId.value = query.addressId
  }
  if (query.stationId) {
    selectedStationId.value = query.stationId
  }
})

onShow(async () => {
  // U07/U14 选择返回：草稿只消费一次，覆盖当前选择（store/delivery-draft 契约）。
  const draft = consumeDeliveryDraft()
  if (draft.addressId) {
    selectedAddressId.value = draft.addressId
  }
  if (draft.stationId) {
    selectedStationId.value = draft.stationId
  }
  await loadData()
})

async function loadData() {
  loading.value = true
  try {
    // real 模式不读 Mock 地址簿（地址随单直填），其余数据源走真实接口；Mock 流程保持原样。
    const [addressList, stationList, waterTypeList, card] = await Promise.all([
      cardApi.listDeliveryAddresses().catch(() => [] as DeliveryAddress[]),
      catalogApi.listStations(),
      catalogApi.listWaterTypes(),
      cardApi.getPrimaryCard(),
    ])
    addresses.value = addressList
    stations.value = stationList
    waterTypes.value = waterTypeList.filter(item => item.enabled)
    primaryCard.value = card
    // 参数/草稿指向的对象不存在时清空回退；地址默认选 isDefault。
    if (!addresses.value.some(item => item.addressId === selectedAddressId.value)) {
      selectedAddressId.value = ''
    }
    if (!selectedAddressId.value) {
      const fallback = addresses.value.find(item => item.isDefault) ?? addresses.value[0]
      selectedAddressId.value = fallback?.addressId ?? ''
    }
    // 地址簿为空时自动进入手动填写，避免用户面对一个空选择器
    if (!addresses.value.length) {
      manualAddress.value = true
    }
    if (!stations.value.some(item => item.id === selectedStationId.value)) {
      selectedStationId.value = ''
    }
    if (!waterTypes.value.some(item => item.id === waterTypeId.value)) {
      waterTypeId.value = ''
    }
  }
  catch (error) {
    toast.show(error instanceof ContractError ? error.message : '页面数据加载失败，请返回重试')
  }
  finally {
    loading.value = false
  }
}

/** 时间戳 → yyyyMMddHHmmss：预约时间按用户选择的钟面时间落契约字段，秒固定 00。 */
function toBusinessTime(timestamp: number): string {
  const value = new Date(timestamp)
  const pad = (input: number) => String(input).padStart(2, '0')
  return (
    `${value.getFullYear()}${pad(value.getMonth() + 1)}${pad(value.getDate())}`
    + `${pad(value.getHours())}${pad(value.getMinutes())}00`
  )
}

async function handleSubmit() {
  if (submitting.value) {
    return
  }
  if (manualAddress.value) {
    // 手动填写：形态校验只给友好提示，最终校验在服务端
    if (!receiveAddress.value.trim()) {
      toast.show('请填写收水地址')
      return
    }
    if (!/^1\d{10}$/.test(receivePhone.value.trim())) {
      toast.show('请填写 11 位收货手机号')
      return
    }
  }
  else if (!selectedAddressId.value) {
    toast.show('请先选择收货地址')
    return
  }
  if (!selectedStationId.value) {
    toast.show('请先选择配送水站')
    return
  }
  if (!waterTypeId.value) {
    toast.show('请选择水种')
    return
  }
  if (deliveryMode.value === 'scheduled' && !scheduledTimestamp.value) {
    toast.show('请选择预约配送时间')
    return
  }
  // 支付方式预检提示（最终裁决在服务端扣减事务）：带着禁用项提交只会得到同一拒因
  const selectedOption = payWay.value === 3 ? mlOption.value : balanceOption.value
  if (selectedOption.disabled) {
    toast.show(selectedOption.reason ?? '当前支付方式不可用')
    return
  }
  submitting.value = true
  try {
    const { order } = await deliveryApi.createDeliveryOrder({
      addressId: manualAddress.value ? undefined : selectedAddressId.value,
      stationId: selectedStationId.value,
      waterTypeId: waterTypeId.value,
      containerSpec: containerSpec.value,
      deliveryCount: deliveryCount.value,
      plannedReturnCount: plannedReturnCount.value,
      deliveryMode: deliveryMode.value,
      scheduledTime: deliveryMode.value === 'scheduled'
        ? toBusinessTime(Number(scheduledTimestamp.value))
        : undefined,
      autoRefillIntervalDays: deliveryMode.value === 'auto-refill'
        ? autoRefillIntervalDays.value
        : undefined,
      requestId: requestId.value,
      receiveAddress: manualAddress.value ? receiveAddress.value.trim() : undefined,
      receivePhone: manualAddress.value ? receivePhone.value.trim() : undefined,
      payWay: payWay.value,
    })
    // 本次提交意图已完成：换新幂等键，防止下一单误复用旧键命中旧订单。
    requestId.value = newDeliveryRequestId()
    // 成功后不复位 submitting：确认弹框关闭即 redirectTo 离开本页，避免二次提交。
    const paidByMl = payWay.value === 3
    // 同上：不留「演示下单，未真实扣款」的回落文案——扣款已经发生，说没扣就是假话。
    message
      .alert({
        title: '下单成功',
        msg: paidByMl
          ? `已抵扣水量 ${formatMl(waterMlPreview.value)}，配送费从余额扣除。`
          : '已从水卡余额扣款。',
      })
      .then(() => redirectTo('U06', { orderNo: order.order.orderNo }))
  }
  catch (error) {
    // 失败保留页面上的全部草稿（含幂等键：同一提交意图重试恒同单），仅提示契约错误信息。
    submitting.value = false
    toast.show(error instanceof ContractError ? error.message : '配送下单失败，请重试')
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="配送下单" back-to="U01" />
    <wd-toast />
    <wd-message-box />

    <AppPrototypeNotice :text="noticeText" />

    <view v-if="loading" class="page-section muted-text">
      配送下单数据加载中…
    </view>

    <template v-else>
      <view class="page-section">
        <wd-cell-group title="收货与水站" border>
          <!-- 地址簿已接服务端（2026-08-02）：选地址传 addressId，号码由服务端解引用写快照、不经前端；
               也可切手动填写（快照式直填，服务端校验）。 -->
          <wd-cell
            v-if="!manualAddress"
            title="收货地址"
            icon="location"
            required
            is-link
            center
            :value="selectedAddress
              ? `${selectedAddress.contactName} ${selectedAddress.maskedPhone}`
              : '请选择收货地址'"
            :label="selectedAddress
              ? `${selectedAddress.region}${selectedAddress.detail}`
              : undefined"
            @click="goTo('U14', { returnTo: 'delivery' })"
          />
          <template v-else>
            <wd-textarea
              v-model="receiveAddress"
              label="收水地址"
              required
              :maxlength="200"
              auto-height
              placeholder="请填写完整收水地址（小区/楼栋/门牌）"
            />
            <wd-input
              v-model="receivePhone"
              label="收货电话"
              required
              type="number"
              :maxlength="11"
              placeholder="请输入 11 位手机号"
            />
          </template>
          <wd-cell
            :title="manualAddress ? '改用地址簿选择' : '改为手动填写'"
            icon="edit-outline"
            is-link
            center
            @click="manualAddress = !manualAddress"
          />
          <wd-cell
            title="配送水站"
            icon="goods"
            required
            is-link
            center
            :value="selectedStation ? selectedStation.stationName : '请选择水站'"
            :label="selectedStation ? selectedStation.address : undefined"
            @click="goTo('U07', { selectMode: 'delivery' })"
          />
        </wd-cell-group>
      </view>

      <view class="page-section">
        <wd-cell-group title="配送内容" border>
          <wd-picker
            v-model="waterTypeId"
            :columns="waterTypeColumns"
            label="水种"
            placeholder="请选择水种"
            title="选择水种"
            required
          />
          <wd-picker
            v-model="containerSpec"
            :columns="CONTAINER_SPECS"
            label="容器规格"
            title="选择容器规格"
            required
          />
          <wd-cell title="配送数量" required center>
            <wd-input-number v-model="deliveryCount" :min="1" />
          </wd-cell>
          <wd-cell title="预计回收空桶" center>
            <wd-input-number v-model="plannedReturnCount" :min="0" />
          </wd-cell>
        </wd-cell-group>
      </view>

      <view class="page-section">
        <wd-cell-group title="配送方式" border>
          <wd-radio-group v-model="deliveryMode" cell>
            <wd-radio value="immediate">
              即时配送
            </wd-radio>
            <wd-radio value="scheduled">
              预约配送
            </wd-radio>
            <wd-radio value="auto-refill">
              自动补货
            </wd-radio>
          </wd-radio-group>
          <wd-datetime-picker
            v-if="deliveryMode === 'scheduled'"
            v-model="scheduledTimestamp"
            label="预约时间"
            placeholder="请选择未来时间"
            title="选择预约配送时间"
            :min-date="minScheduledDate"
            required
          />
          <wd-cell
            v-if="deliveryMode === 'auto-refill'"
            title="补货周期（天）"
            label="3~90 天"
            center
          >
            <wd-input-number v-model="autoRefillIntervalDays" :min="3" :max="90" />
          </wd-cell>
        </wd-cell-group>
      </view>

      <view class="page-section">
        <wd-card title="费用预览">
          <view
            v-for="line in priceLines"
            :key="line.label"
            class="fee-row"
            :class="{ 'fee-total': line.total }"
          >
            <view>{{ line.label }}</view>
            <view>{{ line.value }}</view>
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-cell-group title="支付方式" border>
          <wd-radio-group v-model="payWay" cell>
            <wd-radio :value="2" :disabled="balanceOption.disabled">
              {{ PAY_WAY_LABELS[2] }}（水费+配送费均扣余额）
            </wd-radio>
            <view class="pay-option-note muted-text" :class="{ 'pay-option-blocked': balanceOption.disabled }">
              {{ balanceOptionNote }}
            </view>
            <wd-radio :value="3" :disabled="mlOption.disabled">
              水量抵扣水费 + 余额付配送费
            </wd-radio>
            <view class="pay-option-note muted-text" :class="{ 'pay-option-blocked': mlOption.disabled }">
              {{ mlOptionNote }}
            </view>
          </wd-radio-group>
          <wd-cell :title="PAY_WAY_LABELS[1]" center>
            <wd-tag plain>
              暂不支持
            </wd-tag>
          </wd-cell>
        </wd-cell-group>
      </view>

      <view class="page-section">
        <wd-button block size="large" :loading="submitting" @click="handleSubmit">
          {{ payWay === 3 ? '提交配送订单（水量抵扣+余额配送费）' : '提交配送订单（水卡余额支付）' }}
        </wd-button>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.fee-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 14px;
}

.fee-total {
  font-size: 16px;
  font-weight: 600;
}

.pay-option-note {
  padding: 0 16px 8px;
  font-size: 12px;
  line-height: 1.6;
}

.pay-option-blocked {
  color: var(--wot-color-danger, #fa4350);
}
</style>
