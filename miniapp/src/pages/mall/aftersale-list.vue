<script setup lang="ts">
import type { MallAfterSale } from '@/api/mall-aftersale'
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { ContractError } from '@/api/common'
import { mallAfterSaleApi } from '@/api/mall-aftersale'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import { formatBizTime, formatFen, MALL_AFTER_SALE_STATUS_TONES } from '@/utils/format'
import { goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '我的售后',
  },
})

/**
 * 售后列表：本人全部售后单，状态与金额都直接用服务端下发的口径。
 *
 * 不做前端筛选分页：售后单量级远小于订单，把服务端返回的全集原样列出，用户看到的条数
 * 就是实际存在的条数——本地过滤一旦与服务端口径错位，用户会以为售后单丢了。
 */

const loading = ref(true)
const errorMessage = ref('')
const rows = ref<MallAfterSale[]>([])

onShow(refresh)

async function refresh() {
  loading.value = true
  errorMessage.value = ''
  try {
    rows.value = await mallAfterSaleApi.list()
  }
  catch (error) {
    rows.value = []
    errorMessage.value = error instanceof ContractError ? error.message : '售后列表加载失败，请重试'
  }
  finally {
    loading.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="我的售后" back-to="M01" />

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

    <view v-else-if="!rows.length" class="page-section">
      <AppPageState state="empty" message="还没有售后申请" empty-icon="goods">
        <template #actions>
          <wd-button plain size="small" @click="goTo('M05')">
            查看我的订单
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else>
      <view
        v-for="row in rows"
        :key="row.afterSaleNo"
        class="page-section as-card"
        @click="goTo('M11', { afterSaleNo: row.afterSaleNo })"
      >
        <view class="as-card__head">
          <text class="as-card__type">
            {{ row.afterSaleTypeName }}
          </text>
          <wd-tag :type="MALL_AFTER_SALE_STATUS_TONES[row.afterSaleStatus] || 'default'" plain>
            {{ row.afterSaleStatusName }}
          </wd-tag>
        </view>
        <view class="as-card__row">
          <text class="muted-text">
            售后单号
          </text>
          <text>{{ row.afterSaleNo }}</text>
        </view>
        <view class="as-card__row">
          <text class="muted-text">
            申请时间
          </text>
          <text>{{ formatBizTime(row.applyTime) }}</text>
        </view>
        <view v-if="row.refundAmountFen > 0" class="as-card__row">
          <text class="muted-text">
            退款金额
          </text>
          <text class="money">
            {{ formatFen(row.refundAmountFen) }}
          </text>
        </view>
      </view>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.as-card {
  padding: 12px;
  background: var(--app-bg-card);
  border-radius: var(--r-md);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;
  }

  &__type {
    font-size: var(--fs-title);
    font-weight: 600;
  }

  &__row {
    display: flex;
    gap: 8px;
    align-items: center;
    justify-content: space-between;
    font-size: var(--fs-body);
    line-height: 1.9;
  }
}
</style>
