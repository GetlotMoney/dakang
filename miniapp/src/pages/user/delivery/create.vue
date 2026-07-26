<script setup lang="ts">
import type { CardSummary, DeliveryAddress } from '@/api/card'
import type { StationSummary, WaterType } from '@/api/catalog'
import type { CreateDeliveryOrderInput, DeliveryTask } from '@/api/delivery'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { catalogApi } from '@/api/catalog'
import { ContractError } from '@/api/common'
import {
  CONTAINER_WATER_PRICE_FEN,
  DELIVERY_FEE_PER_CONTAINER_FEN,
  deliveryApi,
  newDeliveryRequestId,
} from '@/api/delivery'
import { currentMode } from '@/api/runtime'
import AppNavbar from '@/components/app-navbar.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import { consumeDeliveryDraft } from '@/store/delivery-draft'
import { formatFen, PAY_WAY_LABELS } from '@/utils/format'
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

/** delivery 域接真：下单/卡/水种/水站全部走真实后端；Mock 构建行为保持原样。 */
const isDeliveryReal = currentMode('delivery') === 'real'

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

// real 无地址簿域（包A 冻结为快照式输入）：收水地址与电话随单直填，服务端最终校验。
const receiveAddress = ref('')
const receivePhone = ref('')

/**
 * 幂等键在一次提交意图内保持不变：失败重试复用同一 requestId（服务端恒返回同单，
 * 不会双扣款）；成功后再换新键。Mock 忽略该字段。
 */
const requestId = ref(newDeliveryRequestId())

const noticeText = isDeliveryReal
  ? '配送下单已接真实接口：提交将从水卡余额真实扣款并生成配送任务；价格为一期占位价目。'
  : '原型演示数据：不触发真实支付、设备指令或微信消息，刷新后重置。'

const selectedAddress = computed(() =>
  addresses.value.find(item => item.addressId === selectedAddressId.value),
)
const selectedStation = computed(() =>
  stations.value.find(item => item.id === selectedStationId.value),
)
const waterTypeColumns = computed(() =>
  waterTypes.value.map(item => ({ label: item.name, value: item.id })),
)

// 费用预览三行实时计算，复用契约导出的价目常量，不另抄数字（蓝图 S06.2）。
const waterAmountFen = computed(
  () => deliveryCount.value * CONTAINER_WATER_PRICE_FEN[containerSpec.value],
)
const deliveryFeeFen = computed(() => deliveryCount.value * DELIVERY_FEE_PER_CONTAINER_FEN)
const totalAmountFen = computed(() => waterAmountFen.value + deliveryFeeFen.value)

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
      isDeliveryReal ? Promise.resolve<DeliveryAddress[]>([]) : cardApi.listDeliveryAddresses(),
      catalogApi.listStations(),
      catalogApi.listWaterTypes(),
      cardApi.getPrimaryCard(),
    ])
    addresses.value = addressList
    stations.value = stationList
    waterTypes.value = waterTypeList.filter(item => item.enabled)
    primaryCard.value = card
    // 参数/草稿指向的对象不存在时清空回退；地址默认选 isDefault（蓝图 §6.3 U08）。
    if (!isDeliveryReal) {
      if (!addresses.value.some(item => item.addressId === selectedAddressId.value)) {
        selectedAddressId.value = ''
      }
      if (!selectedAddressId.value) {
        const fallback = addresses.value.find(item => item.isDefault) ?? addresses.value[0]
        selectedAddressId.value = fallback?.addressId ?? ''
      }
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
  if (isDeliveryReal) {
    // real：地址/电话随单直填（无地址簿域）；形态校验只给友好提示，最终校验在服务端
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
  submitting.value = true
  try {
    const { order } = await deliveryApi.createDeliveryOrder({
      addressId: selectedAddressId.value,
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
      receiveAddress: isDeliveryReal ? receiveAddress.value.trim() : undefined,
      receivePhone: isDeliveryReal ? receivePhone.value.trim() : undefined,
    })
    // 本次提交意图已完成：换新幂等键，防止下一单误复用旧键命中旧订单。
    requestId.value = newDeliveryRequestId()
    // 成功后不复位 submitting：确认弹框关闭即 redirectTo 离开本页，避免二次提交。
    message
      .alert(isDeliveryReal
        ? { title: '下单成功', msg: '已从水卡余额扣款并生成配送任务，可在订单详情跟踪配送与签收进度。' }
        : { title: '下单成功（原型）', msg: '订单已按水卡余额原型支付并生成配送任务（不改变卡面余额，不发生真实结算）' })
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
          <!-- real：无地址簿域（包A 快照式输入），地址与电话随单直填；Mock 保持地址簿选择 -->
          <template v-if="isDeliveryReal">
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
            v-else
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
              : '可在地址列表新增水配送地址'"
            @click="goTo('U14', { returnTo: 'delivery' })"
          />
          <wd-cell
            title="配送水站"
            icon="goods"
            required
            is-link
            center
            :value="selectedStation ? selectedStation.stationName : '请选择水站'"
            :label="selectedStation ? selectedStation.address : '从附近水站选择供水站点'"
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
          <wd-cell title="预计回收空桶" label="回收数量为结构化字段（REQ-060）" center>
            <wd-input-number v-model="plannedReturnCount" :min="0" />
          </wd-cell>
        </wd-cell-group>
        <view class="field-note muted-text">
          水种名称为占位名，实际供水水种待甲方确认。
        </view>
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
            label="按用户配置的固定周期补货，非 AI 预测（REQ-011），支持 3~90 天"
            center
          >
            <wd-input-number v-model="autoRefillIntervalDays" :min="3" :max="90" />
          </wd-cell>
        </wd-cell-group>
      </view>

      <view class="page-section">
        <wd-card title="费用预览">
          <view class="fee-row">
            <view>水费</view>
            <view>{{ formatFen(waterAmountFen) }}</view>
          </view>
          <view class="fee-row">
            <view>配送费</view>
            <view>{{ formatFen(deliveryFeeFen) }}</view>
          </view>
          <view class="fee-row fee-total">
            <view>合计</view>
            <view>{{ formatFen(totalAmountFen) }}</view>
          </view>
          <view class="field-note muted-text">
            {{ isDeliveryReal
              ? '一期占位价目，正式价格待商业确认；提交即从水卡余额真实扣款（水费+配送费）。'
              : '原型价目，正式价格待确认；提交即以水卡余额完成原型支付（不改变卡面余额，不发生真实结算）。' }}
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-cell-group title="支付方式说明" border>
          <wd-cell
            :title="PAY_WAY_LABELS[2]"
            :label="primaryCard
              ? `当前余额 ${formatFen(primaryCard.balanceFen)}${isDeliveryReal ? '（下单即真实扣款）' : '（原型扣减，不改变卡面余额）'}`
              : '当前账号暂无水卡'"
            center
          >
            <wd-tag type="primary" plain>
              本单默认
            </wd-tag>
          </wd-cell>
          <wd-cell :title="PAY_WAY_LABELS[1]" center>
            <wd-tag plain>
              待接入
            </wd-tag>
          </wd-cell>
        </wd-cell-group>
      </view>

      <view class="page-section">
        <wd-button block size="large" :loading="submitting" @click="handleSubmit">
          {{ isDeliveryReal ? '提交配送订单（水卡余额支付）' : '提交配送订单（水卡余额原型支付）' }}
        </wd-button>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.field-note {
  margin-top: 8px;
  padding: 0 4px;
  line-height: 1.6;
}

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
</style>
