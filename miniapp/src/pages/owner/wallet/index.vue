<script setup lang="ts">
import type { OwnerWallet } from '@/api/device'
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { ContractError } from '@/api/common'
import { deviceApi } from '@/api/device'
import { createIdentityRequestId, identityApi } from '@/api/identity'
import { isDemoMode } from '@/api/runtime'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import { formatBizTimeShort, formatFen, INCOME_FLOW_TYPE_LABELS, INCOME_RECEIVER_TYPE_LABELS, pendingSplitLineText } from '@/utils/format'
import { backOr } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '收益钱包',
  },
})

/** 必须讲清与经营概览的关系：那边是订单总额，这里是分完账剩给你的，两个数字本来就不一样。 */
const CALIBER_NOTE
  = '分账后净额，与经营概览的订单总额不同'

const loading = ref(true)
const errorMessage = ref('')
const wallet = ref<OwnerWallet | null>(null)
const withdrawing = ref(false)
const message = useMessage()
const toast = useToast()
const demoMode = isDemoMode()

let fetching = false

onShow(refresh)

async function refresh() {
  if (fetching) {
    return
  }
  fetching = true
  loading.value = true
  errorMessage.value = ''
  try {
    wallet.value = await deviceApi.getOwnerWallet()
  }
  catch (error) {
    wallet.value = null
    errorMessage.value = error instanceof ContractError ? error.message : '钱包数据加载失败，请稍后重试'
  }
  finally {
    fetching = false
    loading.value = false
  }
}

function parseYuanFen(value: string): number | null {
  const match = value.trim().match(/^(\d+)(?:\.(\d{1,2}))?$/)
  if (!match)
    return null
  return Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'))
}

async function withdraw() {
  if (!wallet.value || withdrawing.value)
    return
  let result
  try {
    result = await message.prompt({
      title: '模拟提现',
      inputPlaceholder: '最低1元，例如 10.00',
      inputPattern: /^\d+(?:\.\d{1,2})?$/,
      inputError: '请输入正确金额，最多两位小数',
      confirmButtonText: '确认提现',
    })
  }
  catch { return }
  const fen = parseYuanFen(String(result?.value ?? ''))
  if (fen === null || fen < 100) {
    toast.error('最低提现金额为1元')
    return
  }
  withdrawing.value = true
  try {
    const withdrawNo = await identityApi.simulateWithdraw(fen, createIdentityRequestId())
    toast.success(`模拟打款成功：${withdrawNo}`)
    await refresh()
  }
  catch (error) { toast.error(error instanceof Error ? error.message : '提现失败') }
  finally { withdrawing.value = false }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="收益钱包" back-to="U03" />
    <wd-toast /><wd-message-box />

    <view v-if="loading" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, { width: '60%' }]" />
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain size="small" @click="backOr('U03')">
            返回
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else-if="wallet">
      <view class="page-section">
        <wd-card title="分润余额">
          <view class="balance-line">
            {{ formatFen(wallet.balanceFen) }}
          </view>
          <view v-if="pendingSplitLineText(wallet.pendingSplitFen, wallet.earliestUnfreezeTime)" class="muted-text">
            {{ pendingSplitLineText(wallet.pendingSplitFen, wallet.earliestUnfreezeTime) }}
          </view>
          <view v-if="wallet.clawbackDeficitFen > 0" class="muted-text">
            退款扣回待补 {{ formatFen(wallet.clawbackDeficitFen) }}，提现暂不可用
          </view>
          <view v-if="wallet.frozenFen > 0" class="muted-text">
            另有 {{ formatFen(wallet.frozenFen) }} 提现审核冻结中
          </view>
          <view class="muted-text caliber-note">
            {{ CALIBER_NOTE }}
          </view>
          <view v-if="demoMode" class="withdraw-row">
            <wd-button size="small" :loading="withdrawing" :disabled="wallet.balanceFen < 100 || wallet.clawbackDeficitFen > 0" @click="withdraw">
              提现
            </wd-button>
            <text class="muted-text">
              最低1元，演示环境自动模拟打款
            </text>
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card title="身份收益构成">
          <view v-if="wallet.roleSummaries.length" class="role-summary-list">
            <view v-for="role in wallet.roleSummaries" :key="role.receiverType" class="role-summary-row">
              <view>{{ INCOME_RECEIVER_TYPE_LABELS[role.receiverType] ?? `职责${role.receiverType}` }}</view>
              <view class="role-summary-amount">
                <text>累计 {{ formatFen(role.settledFen) }}</text>
                <text v-if="role.pendingFen > 0" class="muted-text">
                  在途 {{ formatFen(role.pendingFen) }}
                </text>
              </view>
            </view>
          </view>
          <view v-else class="muted-text">
            暂无按身份产生的分润
          </view>
          <view class="muted-text caliber-note">
            各身份分别留痕；可用余额仍汇总到上方统一钱包提现
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card title="收益流水（近 50 条）">
          <template v-if="wallet.flows.length">
            <view v-for="(flow, index) in wallet.flows" :key="index" class="flow-row">
              <view class="flow-main">
                <view>{{ INCOME_FLOW_TYPE_LABELS[flow.flowType] ?? `类型${flow.flowType}` }}</view>
                <view class="muted-text">
                  {{ flow.orderNo ? `订单 ${flow.orderNo}` : '—' }}<template v-if="flow.receiverType">
                    · {{ INCOME_RECEIVER_TYPE_LABELS[flow.receiverType] ?? `职责${flow.receiverType}` }}
                  </template> · {{ formatBizTimeShort(flow.createTime) }}
                </view>
              </view>
              <view class="flow-side">
                <view :class="flow.amountFen >= 0 ? 'amount-in' : 'amount-out'">
                  {{ flow.amountFen >= 0 ? '+' : '' }}{{ formatFen(flow.amountFen) }}
                </view>
                <view class="muted-text">
                  余 {{ formatFen(flow.afterFen) }}
                </view>
              </view>
            </view>
          </template>
          <view v-else class="muted-text">
            暂无分润流水
          </view>
        </wd-card>
      </view>

      <view v-if="!demoMode" class="page-section readonly-footer">
        <wd-icon name="lock-on" size="14px" color="var(--app-text-secondary)" />
        <text>暂不支持提现</text>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.balance-line {
  font-size: 26px;
  font-weight: 700;
  margin-bottom: 6px;
}
.withdraw-row { display: flex; align-items: center; gap: var(--sp-3); margin-top: var(--sp-4); }

.caliber-note {
  margin-top: 8px;
  line-height: 1.6;
}

.flow-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;

  & + & {
    border-top: 1px solid var(--line-1);
  }
}

.flow-side {
  text-align: right;
}

.role-summary-row { display: flex; justify-content: space-between; gap: var(--sp-3); padding: var(--sp-2) 0; }
.role-summary-row + .role-summary-row { border-top: 1px solid var(--line-1); }
.role-summary-amount { display: flex; flex-direction: column; align-items: flex-end; }

.amount-in {
  color: var(--app-color-success);
  font-weight: 600;
}

.amount-out {
  color: var(--app-color-danger);
  font-weight: 600;
}

.readonly-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 12px 0;
  color: var(--app-text-secondary);
  font-size: 13px;
}
</style>
