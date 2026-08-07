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

/** 价格快照演示：套餐售价 payAmountFen / 兑换水量 waterMl 折算为 元/升，保留两位小数。 */
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
    // packageId 参数只做预选中；不在套餐列表内则忽略，等待用户自行选择。
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
    toast.show(isPurchase.value ? '请先选择购卡套餐' : '请先选择充值套餐')
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
            当前账号暂无水卡，请选择套餐购卡。
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card :title="isPurchase ? '选择购卡套餐' : '选择充值套餐'" custom-class="block-card">
          <wd-status-tip
            v-if="!loading && isPurchase && visiblePackages.length === 0"
            image="content"
            tip="暂无可购套餐，请联系客服"
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
            折算单价约 {{ unitPriceText }} 元/升
          </view>
        </wd-card>
      </view>

      <!--
        这里曾有一张「支付能力」卡：按构建期 recharge 模式渲染「模拟支付／可用」，
        并附一句「模拟支付不实际扣款，充值余额真实到账」。两点致命：
        1) 出货三份 env 都是 recharge=real，卡片恒定播报同一句，等于把内部接入进度
           挂在用户面前，用户据此做不了任何决定；
        2) 换上 requestPayment 那天，「不实际扣款」会变成对着真扣款说不扣——
           这是本轮最危险的一类残留，宁可不说也不能说反。
        某笔钱究竟走没走真微信，由服务端 paySource 逐单记录，订单详情的「支付来源」
        照实展示，接真前后都成立，不需要本页再声明一次。
      -->
      <view class="page-section">
        <wd-button
          block
          size="large"
          :loading="submitting"
          :disabled="loading || (isPurchase ? visiblePackages.length === 0 : !card)"
          @click="handleSubmit"
        >
          {{ isPurchase ? '创建购卡订单' : '创建充值订单' }}
        </wd-button>
        <view v-if="!isRechargeMock" class="muted-text meta-note">
          {{ isPurchase
            ? '有效期从支付成功时间起算。'
            : '充值到账后卡有效期顺延套餐天数。' }}
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
</style>
