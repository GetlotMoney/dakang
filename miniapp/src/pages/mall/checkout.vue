<script setup lang="ts">
import type { DeliveryAddress } from '@/api/card'
import type { MallCheckoutPreview, MallOrderLineInput } from '@/api/mall-trade'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { ContractError } from '@/api/common'
import { createMallRequestId, decodeCheckoutLines, mallTradeApi } from '@/api/mall-trade'
import AppBottomActionBar from '@/components/app-bottom-action-bar.vue'
import AppNavbar from '@/components/app-navbar.vue'
import PhoneBindSheet from '@/components/phone-bind-sheet.vue'
import MallGoodsLine from '@/components/mall-goods-line.vue'
import AppPageState from '@/components/app-page-state.vue'
import { formatFen, formatSpecs } from '@/utils/format'
import { goTo, redirectTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '确认订单',
  },
})

const toast = useToast()

const loading = ref(true)
const errorMessage = ref('')
const submitting = ref(false)
/** 绑号半屏可见性：撞到 627 时弹出，绑完由用户自己重新发起，不自动重放。 */
const phoneBindVisible = ref(false)
const addresses = ref<DeliveryAddress[]>([])
const selectedAddressId = ref('')
const addressPickerVisible = ref(false)
const preview = ref<MallCheckoutPreview | null>(null)
const checkoutInputValid = ref(true)

/** 本次结算要买的行，来自购物车勾选或商品详情「立即购买」，页面内只读不再变动。 */
let checkoutLines: MallOrderLineInput[] = []
/**
 * 本次结算会话的创单请求号：在页面装载时生成一次并全程持有。
 * 提交失败重试不换号——同一次确认就是同一笔生意，换号会在超时重发时下出第二张单。
 */
let requestId = ''

onLoad((query) => {
  requestId = createMallRequestId()
  try {
    checkoutLines = decodeCheckoutLines(typeof query?.lines === 'string' ? query.lines : '')
  }
  catch (error) {
    checkoutLines = []
    checkoutInputValid.value = false
    errorMessage.value = error instanceof ContractError ? error.message : '结算信息有误，请重新选择'
  }
})

// onShow 而非 onLoad 触发装载：从地址编辑页返回后立即按新地址重新试算
onShow(refresh)

async function refresh() {
  if (!checkoutLines.length) {
    loading.value = false
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    addresses.value = await cardApi.listDeliveryAddresses()
    const stillThere = addresses.value.some(item => item.addressId === selectedAddressId.value)
    if (!stillThere) {
      const fallback = addresses.value.find(item => item.isDefault) ?? addresses.value[0]
      selectedAddressId.value = fallback?.addressId ?? ''
    }
    preview.value = selectedAddressId.value
      ? await mallTradeApi.checkoutPreview({
          addressId: selectedAddressId.value,
          lines: checkoutLines,
        })
      : null
  }
  catch (error) {
    preview.value = null
    errorMessage.value = error instanceof ContractError ? error.message : '结算信息加载失败，请重试'
  }
  finally {
    loading.value = false
  }
}

const selectedAddress = computed(() =>
  addresses.value.find(item => item.addressId === selectedAddressId.value) ?? null,
)

/**
 * 区县码缺失就地拦截：服务端预览也会拒（唯一裁决仍在服务端），但那句拒绝理由要等一个
 * 来回才到，且用户看不出是哪张地址的问题。就地判一次能在按钮上立刻给出结论并指路。
 */
const districtMissing = computed(() =>
  Boolean(selectedAddress.value) && !selectedAddress.value?.districtCode,
)

const blockText = computed(() => {
  if (districtMissing.value) {
    return '该地址还没有填写区县编码，请补充后再下单'
  }
  return preview.value && !preview.value.submittable
    ? preview.value.blockReason || '当前地址暂时无法下单'
    : ''
})

const canSubmit = computed(() =>
  Boolean(preview.value?.submittable) && !districtMissing.value && Boolean(selectedAddressId.value),
)

function chooseAddress(item: DeliveryAddress) {
  addressPickerVisible.value = false
  if (item.addressId === selectedAddressId.value) {
    return
  }
  selectedAddressId.value = item.addressId
  refresh()
}

function editSelectedAddress() {
  addressPickerVisible.value = false
  if (selectedAddressId.value) {
    goTo('U15', { addressId: selectedAddressId.value })
    return
  }
  goTo('U15')
}

async function handleSubmit() {
  // 放行条件只会比服务端更严：预览说不能提交就一定不提交，前端不自行放宽
  if (submitting.value || !canSubmit.value) {
    return
  }
  submitting.value = true
  try {
    const order = await mallTradeApi.createOrder({
      addressId: selectedAddressId.value,
      lines: checkoutLines,
      requestId,
    })
    // 成功后不复位 submitting：直达订单详情，避免同一页面再次提交
    toast.success('下单成功')
    setTimeout(() => redirectTo('M06', { orderNo: order.orderNo }), 500)
  }
  catch (error) {
    submitting.value = false
    // 绑号闸（627）：商城下单要占库存、要配送员按这个账号上门。
    // 就地弹绑号半屏，绑完用户可再点一次下单——不自动重放，避免用户以为只是绑号却下了单。
    if (error instanceof ContractError && error.code === 'PHONE_BIND_REQUIRED') {
      phoneBindVisible.value = true
      return
    }
    toast.show(error instanceof ContractError ? error.message : '下单失败，请重试')
    // 库存/上架状态可能已变，回读一次最新试算结果
    refresh()
  }
}
</script>

<template>
  <view class="page-shell" :class="{ 'page-shell--with-bar': preview }">
    <PhoneBindSheet v-model="phoneBindVisible" />
    <AppNavbar title="确认订单" back-to="M03" />
    <wd-toast />

    <view v-if="loading" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, 1, { width: '70%' }]" />
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button v-if="checkoutInputValid" plain size="small" @click="refresh">
            重新加载
          </wd-button>
          <wd-button plain size="small" @click="goTo('M03')">
            返回购物车
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else>
      <view class="page-section address-card surface-card">
        <template v-if="preview">
          <view class="address-head">
            <wd-icon name="location" size="18px" color="var(--app-color-primary)" />
            <text class="address-contact">
              {{ preview.receiverName }}
            </text>
            <text class="muted-text">
              {{ preview.maskedPhone }}
            </text>
          </view>
          <text class="address-text">
            {{ preview.receiverRegion }} {{ preview.receiverAddress }}
          </text>
          <view class="address-actions">
            <wd-button size="small" plain @click="addressPickerVisible = true">
              更换地址
            </wd-button>
            <wd-button size="small" plain @click="editSelectedAddress">
              编辑地址
            </wd-button>
          </view>
        </template>
        <template v-else>
          <text class="address-empty">
            还没有收货地址
          </text>
          <view class="address-actions">
            <wd-button size="small" @click="editSelectedAddress">
              新增地址
            </wd-button>
          </view>
        </template>
      </view>

      <view v-if="blockText" class="page-section block-row">
        <wd-notice-bar
          type="warning"
          wrapable
          :scrollable="false"
          :text="blockText"
        />
        <wd-button v-if="districtMissing" size="small" plain @click="editSelectedAddress">
          去补充区县编码
        </wd-button>
      </view>

      <view v-if="preview" class="page-section goods-card">
        <MallGoodsLine
          v-for="line in preview.lines"
          :key="line.skuId"
          :name="line.productName"
          :spec="formatSpecs(line.specs, line.skuName)"
          :price="formatFen(line.salePriceFen)"
          :quantity="line.quantity"
          :cover-url="line.coverUrl"
        />
      </view>

      <view v-if="preview" class="page-section amount-card">
        <view class="amount-row">
          <text>商品金额</text>
          <text>{{ formatFen(preview.productAmountFen) }}</text>
        </view>
        <view class="amount-row">
          <text>配送费</text>
          <text>{{ formatFen(preview.deliveryFeeFen) }}</text>
        </view>
        <view v-if="preview.warehouseName" class="amount-row">
          <text>发货仓</text>
          <text>{{ preview.warehouseName }}</text>
        </view>
        <view class="amount-row amount-total">
          <text>应付</text>
          <text class="money amount-total-value">
            {{ formatFen(preview.orderAmountFen) }}
          </text>
        </view>
      </view>

      <AppBottomActionBar v-if="preview">
        <template #summary>
          <text class="muted-text">
            应付
          </text>
          <text class="money bar-amount">
            {{ formatFen(preview.orderAmountFen) }}
          </text>
        </template>
        <template #primary>
          <wd-button
            size="large"
            type="primary"
            :loading="submitting"
            :disabled="!canSubmit"
            @click="handleSubmit"
          >
            提交订单
          </wd-button>
        </template>
      </AppBottomActionBar>
    </template>

    <wd-popup v-model="addressPickerVisible" position="bottom" closable>
      <view class="picker-panel">
        <view class="picker-title">
          选择收货地址
        </view>
        <view v-if="!addresses.length" class="muted-text">
          还没有收货地址
        </view>
        <view
          v-for="item in addresses"
          :key="item.addressId"
          class="picker-item"
          :class="{ 'picker-item-active': item.addressId === selectedAddressId }"
          @click="chooseAddress(item)"
        >
          <view class="picker-item-head">
            <text class="address-contact">
              {{ item.contactName }}
            </text>
            <text class="muted-text">
              {{ item.maskedPhone }}
            </text>
            <wd-tag v-if="item.isDefault" type="primary" plain>
              默认
            </wd-tag>
          </view>
          <text class="muted-text">
            {{ item.region }} {{ item.detail }}
          </text>
          <view v-if="!item.districtCode" class="picker-item-hint">
            未填区县编码，暂不能下单
          </view>
        </view>
        <wd-button block plain size="small" @click="editSelectedAddress">
          新增或编辑地址
        </wd-button>
      </view>
    </wd-popup>
  </view>
</template>

<style lang="scss" scoped>
// 地址是结算页的第一决策：收货人一行、详址一行两层分明，前面用定位图标锚住
.address-card .address-head {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
}

.address-card .address-contact {
  font-size: var(--fs-title);
  font-weight: 600;
}

.address-card .address-text {
  display: block;
  margin-top: var(--sp-2);
  color: var(--app-text-secondary);
  font-size: var(--fs-body);
  line-height: 1.5;
}

.address-card,
.goods-card,
.amount-card {
  padding: 12px;
  background: var(--app-bg-card);
  border-radius: var(--r-md);
}

.address-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.address-contact {
  font-size: var(--fs-body);
  font-weight: 600;
}

.address-text {
  display: block;
  margin-top: 4px;
  font-size: var(--fs-caption);
  line-height: 1.6;
}

.address-empty {
  display: block;
  font-size: var(--fs-body);
}

.address-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 8px;
}

.goods-line {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.goods-line + .goods-line {
  padding-top: 12px;
  margin-top: 12px;
  border-top: 1px solid var(--line-1);
}

.amount-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--fs-body);
}

.amount-row + .amount-row {
  margin-top: 8px;
}

.amount-total {
  padding-top: 8px;
  border-top: 1px solid var(--line-1);
}

.amount-total-value {
  font-size: var(--fs-metric);
  font-weight: 700;
}

.picker-panel {
  padding: 16px;
}

.picker-title {
  margin-bottom: 12px;
  font-size: var(--fs-title);
  font-weight: 600;
}

.picker-item {
  padding: 10px;
  margin-bottom: 8px;
  background: var(--app-bg-page);
  border-radius: var(--r-sm);
}

.picker-item-active {
  background: var(--tint-primary);
}

.picker-item-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 4px;
}

.picker-item-hint {
  margin-top: 4px;
  font-size: var(--fs-note);
  color: var(--app-color-warning-text);
}

.block-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.bar-amount {
  font-size: var(--fs-title);
  font-weight: 600;
}
</style>
