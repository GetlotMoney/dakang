<script setup lang="ts">
import type { MallFulfill, MallShipment } from '@/api/mall-fulfillment'
import type { MallOrderDetail } from '@/api/mall-trade'
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { ContractError } from '@/api/common'
import { mallFulfillApi } from '@/api/mall-fulfillment'
import { mallTradeApi } from '@/api/mall-trade'
import AppBottomActionBar from '@/components/app-bottom-action-bar.vue'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import BizTimeline from '@/components/biz-timeline.vue'
import MallGoodsLine from '@/components/mall-goods-line.vue'
import {
  formatBizTime,
  formatFen,
  formatSpecs,
  MALL_ORDER_STATUS_LABELS,
  MALL_ORDER_STATUS_TONES,
  mallOrderAmountLabel,
  mallPaySourceLabel,
} from '@/utils/format'
import { goTo } from '@/utils/navigation'
import { createPagePoller } from '@/utils/page-poller'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '订单详情',
  },
})

const toast = useToast()
const message = useMessage()

const loading = ref(true)
const errorMessage = ref('')
const detail = ref<MallOrderDetail | null>(null)
const acting = ref(false)
let orderNo = ''

onLoad((query) => {
  orderNo = typeof query?.orderNo === 'string' ? query.orderNo : ''
})

// 支付、仓库与配送节点可能由其他角色推进；详情可见时每5秒静默同步，离开立即停止。
const orderPoller = createPagePoller(() => refresh(true), 5000)
onShow(() => {
  void refresh()
  orderPoller.start()
})
onHide(orderPoller.stop)
onUnload(orderPoller.stop)

async function refresh(silent = false) {
  if (silent && (loading.value || acting.value)) {
    return
  }
  if (!orderNo) {
    if (!silent) {
      loading.value = false
      errorMessage.value = '缺少订单参数'
    }
    return
  }
  if (!silent) {
    loading.value = true
    errorMessage.value = ''
  }
  try {
    detail.value = await mallTradeApi.orderDetail(orderNo)
    await loadFulfill(detail.value.summary.orderStatus, silent)
  }
  catch (error) {
    if (!silent) {
      detail.value = null
      errorMessage.value = error instanceof ContractError ? error.message : '订单加载失败，请重试'
    }
  }
  finally {
    if (!silent) {
      loading.value = false
    }
  }
}

const fulfill = ref<MallFulfill | null>(null)
const shipments = ref<MallShipment[]>([])
const fulfillLoading = ref(false)
const fulfillErrorMessage = ref('')
/** 包裹区块独立的失败态：它降级不影响履约详情与确认收货入口。 */
const shipmentErrorMessage = ref('')

const fulfillmentExpected = computed(() => {
  const orderStatus = detail.value?.summary.orderStatus
  return orderStatus === 2 || orderStatus === 3 || orderStatus === 4
})

/**
 * 履约信息只在订单进入履约后才有：待支付/已取消订单没有任务，查了也只会拿到错误。
 *
 * 履约读取失败不打断订单详情——订单快照与金额仍要看得到，物流区块单独进入失败态并可重试。
 */
async function loadFulfill(orderStatus: number, silent = false) {
  if (!silent) {
    fulfill.value = null
    shipments.value = []
    fulfillErrorMessage.value = ''
    shipmentErrorMessage.value = ''
  }
  if (orderStatus !== 2 && orderStatus !== 3 && orderStatus !== 4) {
    fulfill.value = null
    shipments.value = []
    fulfillLoading.value = false
    return
  }
  if (!silent) {
    fulfillLoading.value = true
  }
  try {
    fulfill.value = await mallFulfillApi.detail(orderNo)
  }
  catch (error) {
    if (!silent) {
      fulfill.value = null
      fulfillErrorMessage.value = error instanceof ContractError
        ? error.message
        : '物流信息加载失败，请重试'
      fulfillLoading.value = false
    }
    return
  }
  // 包裹单独一段不与履约共用 try：包裹契约异常会连坐置空 fulfill，而「确认收货」挂在 fulfill 上，
  // 次要区块解析失败就能让整张订单永远走不完
  try {
    shipments.value = await mallFulfillApi.shipments(orderNo)
  }
  catch {
    if (!silent) {
      shipments.value = []
      shipmentErrorMessage.value = '承运信息暂时无法显示'
    }
  }
  finally {
    if (!silent) {
      fulfillLoading.value = false
    }
  }
}

/** 第三方物流：承运方回传的运单与轨迹在包裹里，履约时间线只记平台侧节点。 */
const thirdPartyShipments = computed(() => shipments.value.filter(item => item.fulfillMode === 2))

/**
 * 履约节点转时间线条目：纯展示派生，只重排已有字段，不补节点、不推导状态。
 */
const timelineNodes = computed(() =>
  (fulfill.value?.timeline ?? []).map(node => ({
    title: node.traceNodeName,
    time: formatBizTime(node.traceTime),
    description: node.traceText,
    actor: node.actorTypeName,
  })),
)

/** 承运方轨迹转时间线条目：纯展示派生，不补节点、不推导状态。 */
function shipmentTraceNodes(ship: MallShipment) {
  return ship.logisticsTraces.map(node => ({
    title: node.eventStateName,
    time: formatBizTime(node.eventTime),
    description: node.eventDesc,
    actor: ship.providerCode ?? '承运方',
  }))
}

/** 已送达待签收才出签收入口；能否真正签收仍由服务端按归属与状态再判一次。 */
const canSign = computed(() => fulfill.value?.fulfillStatus === 6)

async function handleSign() {
  if (acting.value || !canSign.value) {
    return
  }
  acting.value = true
  try {
    // 签收方式必须显式选择，不默认成本人签收——那是在替用户作证
    const chosen = await uni.showActionSheet({ itemList: ['本人签收', '他人代收'] })
    const signMethod = chosen.tapIndex === 1 ? 2 : 1
    fulfill.value = await mallFulfillApi.sign({ orderNo, signMethod })
    await refresh()
    uni.showToast({ title: '已签收', icon: 'success' })
  }
  catch (error) {
    if (error instanceof ContractError) {
      uni.showToast({ title: error.message, icon: 'none' })
    }
  }
  finally {
    acting.value = false
  }
}

/**
 * 售后入口出现的两种情形，与服务端准入判据同形：
 * 已支付且未开始拣货可整单取消；已完成（已签收）可申请退货或换货。
 *
 * 签收满 7 天后服务端会拒绝，这里不自算窗口——本地时钟与业务时间不是同一个来源，
 * 用它算出的「还能申请 2 天」会和实际结果对不上。
 */
const afterSaleEntry = computed(() => {
  const orderStatus = detail.value?.summary.orderStatus
  if (orderStatus === 2) {
    const notPicked = !fulfill.value || fulfill.value.fulfillStatus <= 1
    return notPicked ? '取消订单退款' : ''
  }
  return orderStatus === 4 ? '申请售后' : ''
})

/** 支付与取消入口只在待支付时出现；能否真正执行仍由服务端再判一次。 */
const pendingPay = computed(() => detail.value?.summary.orderStatus === 1)

const amountLabel = computed(() => {
  const summary = detail.value?.summary
  return summary ? mallOrderAmountLabel(summary.orderStatus, summary.payStatus) : '订单金额'
})

const paySourceText = computed(() => mallPaySourceLabel(detail.value?.paySource))

function handlePay() {
  const summary = detail.value?.summary
  if (acting.value || !summary || !pendingPay.value) {
    return
  }
  message
    .confirm({ title: '支付订单', msg: `即将支付 ${formatFen(summary.orderAmountFen)}` })
    .then(async () => {
      acting.value = true
      try {
        await mallTradeApi.simulatePay(summary.orderNo)
        // 支付接口返回成功只代表「支付事实收到了」；订单是否推进以再读一次的状态为准
        const latest = await mallTradeApi.payStatus(summary.orderNo)
        detail.value = latest
        if (latest.summary.payStatus === 2) {
          toast.success('支付成功')
        }
        else {
          toast.show('支付结果处理中，请稍后刷新查看')
        }
      }
      catch (error) {
        toast.show(error instanceof ContractError ? error.message : '支付未完成，请重试')
        await refresh()
      }
      finally {
        acting.value = false
      }
    })
    .catch(() => null)
}

function handleCancel() {
  const summary = detail.value?.summary
  if (acting.value || !summary || !pendingPay.value) {
    return
  }
  message
    .confirm({ title: '取消订单', msg: '取消后订单不可恢复，确认取消？' })
    .then(async () => {
      acting.value = true
      try {
        await mallTradeApi.cancelOrder(summary.orderNo)
        toast.success('订单已取消')
        await refresh()
      }
      catch (error) {
        toast.show(error instanceof ContractError ? error.message : '取消失败，请重试')
        await refresh()
      }
      finally {
        acting.value = false
      }
    })
    .catch(() => null)
}
</script>

<template>
  <view class="page-shell" :class="{ 'page-shell--with-bar': pendingPay || canSign || !!afterSaleEntry }">
    <AppNavbar title="订单详情" back-to="M05" />
    <wd-toast />
    <wd-message-box />

    <view v-if="loading" class="page-section">
      <AppPageState state="loading" />
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain size="small" @click="refresh()">
            重新加载
          </wd-button>
          <wd-button plain size="small" @click="goTo('M05')">
            返回订单列表
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else-if="detail">
      <view class="page-section status-card">
        <view class="status-head">
          <text class="status-text">
            {{ MALL_ORDER_STATUS_LABELS[detail.summary.orderStatus] }}
          </text>
          <wd-tag :type="MALL_ORDER_STATUS_TONES[detail.summary.orderStatus]" plain>
            {{ detail.summary.itemKindCount }} 种商品
          </wd-tag>
        </view>
        <text v-if="pendingPay && detail.summary.payExpireTime" class="muted-text">
          请在 {{ formatBizTime(detail.summary.payExpireTime) }} 前完成支付
        </text>
        <text v-else-if="detail.cancelReason" class="muted-text">
          取消原因：{{ detail.cancelReason }}
        </text>
      </view>

      <view class="page-section info-card">
        <view class="info-title">
          收货信息
        </view>
        <view class="info-row">
          <text>{{ detail.summary.receiverName }}</text>
          <text class="muted-text">
            {{ detail.summary.maskedPhone }}
          </text>
        </view>
        <text class="info-address muted-text">
          {{ detail.receiverRegion }} {{ detail.receiverAddress }}
        </text>
        <view v-if="detail.summary.warehouseName" class="info-row">
          <text class="muted-text">
            发货仓
          </text>
          <text>{{ detail.summary.warehouseName }}</text>
        </view>
      </view>

      <view class="page-section info-card">
        <view class="info-title">
          商品明细
        </view>
        <MallGoodsLine
          v-for="item in detail.items"
          :key="item.skuId"
          :name="item.productName"
          :spec="formatSpecs(item.specs, item.skuName)"
          :price="formatFen(item.unitPriceFen)"
          :quantity="item.quantity"
        />
      </view>

      <view class="page-section info-card">
        <view class="info-row">
          <text>商品金额</text>
          <text>{{ formatFen(detail.summary.productAmountFen) }}</text>
        </view>
        <view class="info-row">
          <text>配送费</text>
          <text>{{ formatFen(detail.summary.deliveryFeeFen) }}</text>
        </view>
        <view v-if="paySourceText" class="info-row">
          <text>支付方式</text>
          <text>{{ paySourceText }}</text>
        </view>
        <view v-if="detail.paySuccessTime" class="info-row">
          <text>支付时间</text>
          <text>{{ formatBizTime(detail.paySuccessTime) }}</text>
        </view>
        <view class="info-row info-total">
          <text>{{ amountLabel }}</text>
          <text class="money info-total-value">
            {{ formatFen(detail.summary.orderAmountFen) }}
          </text>
        </view>
      </view>

      <view class="page-section info-card">
        <view class="info-row">
          <text class="muted-text">
            订单号
          </text>
          <text>{{ detail.summary.orderNo }}</text>
        </view>
        <view class="info-row">
          <text class="muted-text">
            下单时间
          </text>
          <text>{{ formatBizTime(detail.summary.createTime) }}</text>
        </view>
      </view>

      <view v-if="fulfillmentExpected" class="page-section info-card">
        <view class="info-title">
          物流信息
        </view>
        <AppPageState v-if="fulfillLoading" state="loading" />
        <AppPageState
          v-else-if="fulfillErrorMessage"
          state="error"
          :message="fulfillErrorMessage"
        >
          <template #actions>
            <wd-button plain size="small" @click="loadFulfill(detail.summary.orderStatus)">
              重新加载
            </wd-button>
          </template>
        </AppPageState>
        <template v-else-if="fulfill">
          <view class="info-row">
            <text class="muted-text">
              物流状态
            </text>
            <text>{{ fulfill.fulfillStatusName }}</text>
          </view>
          <view v-if="fulfill.exchangeReshipment" class="info-row">
            <text class="muted-text">
              订单类型
            </text>
            <text>换货补发（无需再次支付）</text>
          </view>
          <view class="info-row">
            <text class="muted-text">
              承运方式
            </text>
            <text>{{ fulfill.fulfillModeName || '待安排' }}</text>
          </view>
          <!-- 配送员只在自营渠道存在；第三方单上不显示这一行，改显运单 -->
          <view v-if="fulfill.fulfillMode === 1 && fulfill.courierName" class="info-row">
            <text class="muted-text">
              配送员
            </text>
            <text>{{ fulfill.courierName }} {{ fulfill.courierPhone }}</text>
          </view>
          <view v-if="shipmentErrorMessage" class="info-row">
            <text class="muted-text">
              承运信息
            </text>
            <text>{{ shipmentErrorMessage }}</text>
          </view>
          <view v-for="ship in thirdPartyShipments" :key="String(ship.shipmentId)" class="shipment-block">
            <view class="info-row">
              <text class="muted-text">
                承运商
              </text>
              <text>{{ ship.providerCode || '-' }}</text>
            </view>
            <view class="info-row">
              <text class="muted-text">
                运单号
              </text>
              <!-- 运单号要等承运方受理才有：如实写"待承运方受理"，写 "-" 会被读成没有运单 -->
              <text>{{ ship.waybillNo || '待承运方受理' }}</text>
            </view>
            <view class="info-row">
              <text class="muted-text">
                包裹状态
              </text>
              <text>{{ ship.shipmentStatusName }}</text>
            </view>
            <BizTimeline
              v-if="ship.logisticsTraces.length"
              :nodes="shipmentTraceNodes(ship)"
            />
          </view>
          <BizTimeline v-if="timelineNodes.length" :nodes="timelineNodes" />
        </template>
        <AppPageState v-else state="empty" message="暂未生成物流信息" />
      </view>

      <AppBottomActionBar v-if="pendingPay || canSign || afterSaleEntry">
        <template #summary>
          <text class="muted-text">
            {{ amountLabel }}
          </text>
          <text class="money bar-amount">
            {{ formatFen(detail.summary.orderAmountFen) }}
          </text>
        </template>
        <template v-if="pendingPay" #secondary>
          <wd-button plain :disabled="acting" @click="handleCancel">
            取消订单
          </wd-button>
        </template>
        <template #primary>
          <wd-button v-if="pendingPay" type="primary" :loading="acting" @click="handlePay">
            去支付
          </wd-button>
          <wd-button v-else-if="canSign" type="primary" :loading="acting" @click="handleSign">
            确认签收
          </wd-button>
          <wd-button v-else type="primary" plain @click="goTo('M09', { orderNo: detail.summary.orderNo })">
            {{ afterSaleEntry }}
          </wd-button>
        </template>
      </AppBottomActionBar>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.status-card,
.info-card {
  padding: 12px;
  background: var(--app-bg-card);
  border-radius: var(--r-md);
}

.status-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.status-text {
  font-size: var(--fs-metric);
  font-weight: 700;
}

.info-title {
  margin-bottom: 8px;
  font-size: var(--fs-title);
  font-weight: 600;
}

.info-row {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  font-size: var(--fs-body);
}

.info-row + .info-row {
  margin-top: 8px;
}

.shipment-block {
  padding-top: 8px;
  margin-top: 8px;
  border-top: 1px solid var(--app-border-weak, rgb(0 0 0 / 8%));
}

.info-address {
  display: block;
  margin-top: 4px;
  font-size: var(--fs-caption);
  line-height: 1.6;
}

.info-total {
  padding-top: 8px;
  border-top: 1px solid var(--line-1);
}

.info-total-value {
  font-size: var(--fs-metric);
  font-weight: 700;
}

.goods-line {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
}

.goods-line + .goods-line {
  padding-top: 8px;
  margin-top: 8px;
  border-top: 1px solid var(--line-1);
}

.bar-amount {
  font-size: var(--fs-title);
  font-weight: 600;
}
</style>
