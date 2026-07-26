<script setup lang="ts">
import type { CardSummary } from '@/api/card'
import type { RechargePackage } from '@/api/recharge'
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { ContractError } from '@/api/common'
import {
  createRechargeRequestId,
  rechargeApi,
  resolveRechargePageMode,
  visiblePackagesForMode,
} from '@/api/recharge'
import { currentMode } from '@/api/runtime'
import AppNavbar from '@/components/app-navbar.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import { CARD_STATUS_LABELS, CARD_STATUS_TONES, formatFen, formatMl } from '@/utils/format'
import { backOr, redirectTo } from '@/utils/navigation'
import { payAndSettle } from '@/utils/recharge-pay'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '充值',
  },
})

const toast = useToast()
const message = useMessage()
const isRechargeMock = currentMode('recharge') === 'mock'
const isCardReal = currentMode('card') === 'real'

const loading = ref(true)
const loadError = ref('')
const card = ref<CardSummary | null>(null)
const packages = ref<RechargePackage[]>([])
const pageMode = computed(() => resolveRechargePageMode(card.value))
const isPurchase = computed(() => pageMode.value === 'purchase')
const pageTitle = computed(() => loading.value ? '充值' : isPurchase.value ? '首次购卡' : '充值')
const visiblePackages = computed(() => visiblePackagesForMode(packages.value, pageMode.value))

// 仅套餐充值（L2 契约：packageId 必填，自定义金额已按契约移除）。
const selectedPackageId = ref('')
const submitting = ref(false)
/** 客户端幂等键：同一次创建重试复用；改选套餐后重置（见 recharge.ts 契约注释）。 */
const requestId = ref(createRechargeRequestId())

const selectedPackage = computed(
  () => visiblePackages.value.find(item => item.id === selectedPackageId.value) ?? null,
)

const boundaryNotice = computed(() => {
  if (isRechargeMock) {
    return `${isCardReal ? '水卡余额来自真实 card 接口' : '水卡余额为 Mock/固定快照'}；充值套餐与充值订单固定为 Mock。`
  }
  return isPurchase.value
    ? '首次购卡已接真实后端；只展示服务端确认范围配置合法的套餐，支付由 Pay-Sim 模拟，不发生真实扣款。'
    : '充值域已接真：创单、支付事实与入账均走真实后端；当前支付由 Pay-Sim 模拟，不发生真实扣款。'
})

/** 价格快照演示：套餐售价 payAmountFen / 兑换水量 waterMl 折算为 元/升，保留两位小数（蓝图 U10）。 */
const unitPriceText = computed(() => {
  const pkg = selectedPackage.value
  if (!pkg || pkg.waterMl <= 0) {
    return ''
  }
  return (pkg.payAmountFen / 100 / (pkg.waterMl / 1000)).toFixed(2)
})

function packageValidityText(expireDays: number | null): string {
  return expireDays === null ? '永久有效' : `有效期 ${expireDays} 天`
}

onLoad((query) => {
  refresh(query?.cardId ?? '', query?.packageId ?? '')
})

async function refresh(cardId: string, packageId: string) {
  loading.value = true
  loadError.value = ''
  selectedPackageId.value = ''
  try {
    const [cardResult, packageList] = await Promise.all([
      cardId ? cardApi.getCardDetail(cardId) : cardApi.getPrimaryCard(),
      rechargeApi.listPackages(),
    ])
    card.value = cardResult
    packages.value = packageList
    // packageId 参数只做预选中；不在套餐列表内则忽略，等待用户自行选择（蓝图 §6.6 U10 行）。
    const available = visiblePackagesForMode(packageList, resolveRechargePageMode(cardResult))
    if (packageId && available.some(item => item.id === packageId)) {
      selectedPackageId.value = packageId
    }
  }
  catch (error) {
    loadError.value = error instanceof ContractError ? error.message : '充值数据加载失败'
  }
  finally {
    loading.value = false
  }
}

function selectPackage(packageId: string) {
  if (selectedPackageId.value !== packageId) {
    selectedPackageId.value = packageId
    // 改选套餐 = 新一次购买意图：重置幂等键。
    requestId.value = createRechargeRequestId()
  }
}

/** 交互回调注入：付款与到账判定的实现在 utils/recharge-pay，本页只负责怎么弹。 */
const payPrompts = {
  confirm: (msg: string) => message
    .confirm({ title: '确认支付', msg })
    .then(() => true)
    .catch(() => false),
  notify: (kind: 'success' | 'info', msg: string) =>
    kind === 'success' ? toast.success(msg) : toast.show(msg),
}

async function handleSubmit() {
  if (submitting.value) {
    return
  }
  if (!isPurchase.value && !card.value) {
    toast.show('当前账号暂无水卡，无法生成充值订单')
    return
  }
  if (!selectedPackage.value) {
    toast.show(isPurchase.value ? '请先选择购卡套餐' : '请先选择充值套餐（本期仅支持套餐充值）')
    return
  }
  submitting.value = true
  try {
    const created = await rechargeApi.createRechargeOrder({
      ...(isPurchase.value ? {} : { cardId: card.value!.cardId }),
      packageId: selectedPackage.value.id,
      requestId: requestId.value,
    })
    if (isRechargeMock) {
      await message
        .alert({
          title: '提示',
          msg: '已生成待支付原型订单：微信支付未接入，不发生真实扣款、到账或赠送发放；即将进入订单详情。',
        })
        .catch(() => null)
      redirectTo('U06', { orderNo: created.orderNo, source: 'local-mock' })
    }
    else {
      // 付款与到账确认的实现统一在 utils/recharge-pay，与订单详情的「继续支付」共用一份
      const settled = await payAndSettle(created.orderNo, created.payAmountFen, {
        ...payPrompts,
        completedMessage: isPurchase.value ? '新卡已开通，购卡权益已到账' : '充值已到账',
      })
      if (settled === null) {
        // 用户取消确认：留在本页，允许再次发起（订单已创建，可从订单详情继续支付）
        submitting.value = false
        return
      }
      redirectTo('U06', { orderNo: created.orderNo })
    }
  }
  catch (error) {
    toast.error(error instanceof ContractError ? error.message : '创建订单失败，请重试')
    submitting.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar :title="pageTitle" back-to="U03" />
    <wd-toast />
    <wd-message-box />
    <AppPrototypeNotice
      :text="boundaryNotice"
    />

    <template v-if="loadError">
      <view class="page-section">
        <wd-status-tip image="content" :tip="loadError">
          <template #bottom>
            <view class="status-actions">
              <wd-button plain @click="backOr('U03')">
                返回
              </wd-button>
            </view>
          </template>
        </wd-status-tip>
      </view>
    </template>
    <template v-else>
      <view class="page-section">
        <wd-card custom-class="block-card">
          <template #title>
            <view class="card-title-row">
              <view>{{ isPurchase ? '首次购卡' : '当前水卡' }}</view>
              <wd-tag v-if="card" :type="CARD_STATUS_TONES[card.cardStatus]" plain>
                {{ CARD_STATUS_LABELS[card.cardStatus] }}
              </wd-tag>
              <wd-tag v-else-if="!loading && isPurchase" type="primary" plain>
                新开虚拟卡
              </wd-tag>
            </view>
          </template>
          <template v-if="card">
            <view class="card-no-row">
              <wd-icon name="creditcard" size="16px" />
              <view>{{ card.cardNo }}</view>
            </view>
            <view class="card-metrics">
              <view class="card-metric">
                <view class="card-metric-value">
                  {{ formatFen(card.balanceFen) }}
                </view>
                <view class="muted-text">
                  余额
                </view>
              </view>
              <view class="card-metric">
                <view class="card-metric-value">
                  {{ formatMl(card.balanceMl) }}
                </view>
                <view class="muted-text">
                  剩余水量
                </view>
              </view>
            </view>
          </template>
          <view v-else-if="loading" class="muted-text">
            加载中…
          </view>
          <view v-else class="muted-text">
            当前账号暂无水卡。请选择可购套餐，支付成功后系统将创建本人虚拟卡并原子入账权益。
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card :title="isPurchase ? '选择购卡套餐' : '选择充值套餐'" custom-class="block-card">
          <wd-status-tip
            v-if="!loading && isPurchase && visiblePackages.length === 0"
            image="content"
            tip="暂无可购套餐，请联系客服配置"
          />
          <wd-cell-group v-else border>
            <wd-cell
              v-for="pkg in visiblePackages"
              :key="pkg.id"
              :title="pkg.packageName"
              :label="`兑换 ${formatMl(pkg.waterMl)} · 赠送 ${formatFen(pkg.bonusAmountFen)} · ${packageValidityText(pkg.expireDays)}`"
              clickable
              @click="selectPackage(pkg.id)"
            >
              <view class="pkg-value">
                <view class="pkg-price">
                  {{ formatFen(pkg.payAmountFen) }}
                </view>
                <wd-icon
                  v-if="selectedPackageId === pkg.id"
                  name="check-outline"
                  size="18px"
                  color="#5d87ff"
                />
              </view>
            </wd-cell>
          </wd-cell-group>
          <view v-if="unitPriceText" class="muted-text snapshot-note">
            价格快照演示：该套餐折算单价约 {{ unitPriceText }} 元/升，下单后以订单套餐快照为准。
          </view>
          <view class="muted-text snapshot-note">
            {{ isPurchase
              ? '首次购卡只使用服务端确认可购的套餐；新卡类型、到账权益与范围均以订单及发卡结果为准。'
              : '本期仅支持套餐充值（L2 契约：packageId 必填、到账金额由服务端套餐快照决定）；不提供自定义金额。' }}
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card title="支付能力" custom-class="block-card">
          <wd-cell :title="isRechargeMock ? '微信支付' : 'Pay-Sim 模拟支付'" icon="money-circle">
            <wd-tag :type="isRechargeMock ? 'warning' : 'primary'" plain>
              {{ isRechargeMock ? '待接入' : '测试环境可用' }}
            </wd-tag>
          </wd-cell>
          <view class="muted-text pay-note">
            <template v-if="isRechargeMock">
              原型最多生成待支付订单，不发生真实到账；未接入退款与赠送发放。
            </template>
            <template v-else>
              当前由 Pay-Sim 模拟支付，走与真实回调同一条处理链路，{{ isPurchase ? '支付成功后会创建虚拟卡并入账' : '会真实入账到本卡' }}但不发生真实扣款；
              真实微信支付与退款尚未接入。
            </template>
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-button
          block
          size="large"
          :loading="submitting"
          :disabled="loading || (isPurchase ? visiblePackages.length === 0 : !card)"
          @click="handleSubmit"
        >
          {{ isRechargeMock ? '生成待支付原型订单' : isPurchase ? '创建购卡订单' : '创建充值订单' }}
        </wd-button>
        <view class="muted-text meta-note">
          <template v-if="isRechargeMock">
            当前充值域固定为 Mock：生成待支付原型单后进入订单详情，并可从订单 Tab 回看；不生成真实支付、到账或赠送结果。
          </template>
          <template v-else>
            创建订单后会弹出模拟支付确认；到账与否只以服务端返回的状态为准，不以点击成功为准。
            {{ isPurchase
              ? '有限期新卡从支付成功时间起算套餐天数；永久套餐的新卡永久有效。'
              : '有限期套餐到账后按「原有效期与支付时间的较晚者 + 套餐天数」顺延卡有效期。' }}
          </template>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.status-actions {
  display: flex;
  justify-content: center;
  margin-top: 20px;
  width: 100%;
}

.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.card-no-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}

.card-metrics {
  display: flex;
  align-items: center;
  gap: 32px;
  padding: 10px 0 4px;
}

.card-metric-value {
  font-size: 20px;
  font-weight: 600;
}

.pkg-value {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.pkg-price {
  font-size: 15px;
  font-weight: 600;
}

.snapshot-note {
  margin-top: 10px;
  line-height: 1.6;
}

.pay-note {
  margin-top: 8px;
  line-height: 1.6;
}
</style>
