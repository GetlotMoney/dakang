<script setup lang="ts">
import type { OwnerWallet } from '@/api/device'
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { ContractError } from '@/api/common'
import { deviceApi } from '@/api/device'
import AppNavbar from '@/components/app-navbar.vue'
import { formatBizTimeShort, formatFen, INCOME_FLOW_TYPE_LABELS, pendingSplitLineText } from '@/utils/format'
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
</script>

<template>
  <view class="page-shell screen-o06">
    <AppNavbar title="收益钱包" back-to="U03" />

    <view v-if="loading" class="page-section muted-text">
      加载中…
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <wd-status-tip image="network" :tip="errorMessage">
        <template #bottom>
          <view class="status-actions">
            <wd-button plain size="small" @click="backOr('U03')">
              返回
            </wd-button>
          </view>
        </template>
      </wd-status-tip>
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
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card title="收益流水（近 50 条）">
          <template v-if="wallet.flows.length">
            <view v-for="(flow, index) in wallet.flows" :key="index" class="flow-row">
              <view class="flow-main">
                <view>{{ INCOME_FLOW_TYPE_LABELS[flow.flowType] ?? `类型${flow.flowType}` }}</view>
                <view class="muted-text">
                  {{ flow.orderNo ? `订单 ${flow.orderNo}` : '—' }} · {{ formatBizTimeShort(flow.createTime) }}
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

      <view class="page-section readonly-footer">
        <wd-icon name="lock-on" size="14px" color="#646a73" />
        <text>暂不支持提现</text>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.status-actions {
  display: flex;
  justify-content: center;
  margin-top: 16px;
  width: 100%;
}

.balance-line {
  font-size: 26px;
  font-weight: 700;
  margin-bottom: 6px;
}

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
    border-top: 1px solid rgba(0, 0, 0, 0.05);
  }
}

.flow-side {
  text-align: right;
}

.amount-in {
  color: #34d19d;
  font-weight: 600;
}

.amount-out {
  color: #fa4350;
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
