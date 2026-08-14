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
import AppBottomActionBar from '@/components/app-bottom-action-bar.vue'
import AppNavbar from '@/components/app-navbar.vue'
import PhoneBindSheet from '@/components/phone-bind-sheet.vue'
import AppPageState from '@/components/app-page-state.vue'
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
/** 绑号半屏可见性：撞到 627 时弹出，绑完由用户自己重新发起，不自动重放。 */
const phoneBindVisible = ref(false)
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
  catch (error) {
    // 绑号闸（627）：游客态账号没有手机号，而购卡是发行可兑付预付卡。
    // 弹绑号半屏让用户就地绑完接着买，而不是只给一句 toast 让他自己去找入口。
    if (error instanceof ContractError && error.code === 'PHONE_BIND_REQUIRED') {
      phoneBindVisible.value = true
      submitting.value = false
      return
    }
    toast.error(error instanceof ContractError ? error.message : '创建订单失败，请重试')
    submitting.value = false
  }
}
</script>

<template>
  <view class="page-shell page-shell--with-bar">
    <PhoneBindSheet v-model="phoneBindVisible" />
    <AppNavbar :title="pageTitle" back-to="U03" />
    <wd-toast />
    <wd-message-box />

    <template v-if="loadError">
      <view class="page-section">
        <AppPageState state="error" :message="loadError">
          <template #actions>
            <wd-button plain @click="backOr('U03')">
              返回
            </wd-button>
          </template>
        </AppPageState>
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
          <AppPageState v-else-if="loading" state="loading" :row-col="[1, { width: '70%' }]" />
          <view v-else class="muted-text">
            当前账号暂无水卡，请选择套餐购卡。
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card :title="isPurchase ? '选择购卡套餐' : '选择充值套餐'" custom-class="block-card">
          <AppPageState
            v-if="!loading && isPurchase && visiblePackages.length === 0"
            state="empty"
            message="暂无可购套餐，请联系客服"
          />
          <!-- 价格方案行：每行按「名称 / 售价」+「兑换 · 赠送 · 有效期」同一顺序排，
               套餐之间可以逐项对比。选中态用品牌蓝浅底 + 加粗，不用发光描边。 -->
          <view v-else class="pkg-list">
            <view
              v-for="pkg in visiblePackages"
              :key="pkg.id"
              class="pkg-row pressable"
              :class="{ 'pkg-row--selected': selectedPackageId === pkg.id }"
              @click="selectPackage(pkg.id)"
            >
              <view class="pkg-row__head">
                <text class="pkg-row__name">
                  {{ pkg.packageName }}
                </text>
                <text class="pkg-row__price money">
                  {{ formatFen(pkg.payAmountFen) }}
                </text>
              </view>
              <!-- 零值不占位：纯充值套餐 waterMl=0、无赠送套餐 bonus=0，
                   渲染成「兑换 0L」「赠送 ¥0.00」等于用一格空信息挤掉真正能比较的字段 -->
              <view class="pkg-row__meta">
                <text v-if="pkg.waterMl > 0" class="pkg-row__item">
                  兑换 {{ formatMl(pkg.waterMl) }}
                </text>
                <text v-if="pkg.bonusAmountFen > 0" class="pkg-row__item">
                  赠送 {{ formatFen(pkg.bonusAmountFen) }}
                </text>
                <text class="pkg-row__item">
                  {{ packageValidityText(pkg.expireDays) }}
                </text>
              </view>
            </view>
          </view>
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
        <AppBottomActionBar>
          <template #primary>
            <!-- 付款类主按钮通栏：吸底栏无 summary 槽时按钮只按内容宽，
                 320 屏上一颗右对齐的短按钮不像这一屏的主动作 -->
            <wd-button
              block
              size="large"
              type="primary"
              :loading="submitting"
              :disabled="loading || (isPurchase ? visiblePackages.length === 0 : !card)"
              @click="handleSubmit"
            >
              {{ isPurchase ? '创建购卡订单' : '创建充值订单' }}
            </wd-button>
          </template>
        </AppBottomActionBar>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
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

.pkg-list {
  overflow: hidden;
  border-radius: var(--r-sm);
}

.pkg-row {
  padding: var(--sp-3) var(--sp-2);
  border-bottom: 1px solid var(--line-1);

  &:last-child {
    border-bottom: none;
  }

  &--selected {
    border-radius: var(--r-sm);
    background: var(--tint-primary);
    border-bottom-color: transparent;

    .pkg-row__name {
      font-weight: 700;
    }
  }

  &__head {
    display: flex;
    gap: var(--sp-3);
    align-items: baseline;
  }

  &__name {
    display: block;
    flex: 1;
    min-width: 0;
    overflow: hidden;
    font-size: var(--fs-body);
    font-weight: 600;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__price {
    flex: none;
    font-size: var(--fs-title);
    font-weight: 700;
  }

  &__meta {
    display: flex;
    gap: var(--sp-3);
    align-items: baseline;
    margin-top: var(--sp-1);
    color: var(--app-text-tertiary);
    font-size: var(--fs-note);
  }

  &__item {
    flex: none;
  }
}

.snapshot-note {
  margin-top: 10px;
  line-height: 1.6;
}
</style>
