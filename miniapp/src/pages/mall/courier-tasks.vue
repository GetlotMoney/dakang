<script setup lang="ts">
import type { MallFulfill } from '@/api/mall-fulfillment'
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { ContractError } from '@/api/common'
import { mallFulfillApi } from '@/api/mall-fulfillment'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import { formatBizTimeShort } from '@/utils/format'
import { goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '商城配送任务',
  },
})

const loading = ref(true)
const errorMessage = ref('')
const tasks = ref<MallFulfill[]>([])

/**
 * 只列出服务端认定分配给本人的任务。
 *
 * 列表不接受任何 courierId 入参——归属由服务端按会话反查配送员身份判定；
 * 分配证据（任务 COURIER_ID 与分配轨迹 SUBJECT_ID 共键）不一致时服务端会整份拒绝，
 * 此处如实进失败态，绝不退化成"先显示出来再说"。
 */
async function loadTasks() {
  loading.value = true
  errorMessage.value = ''
  try {
    tasks.value = await mallFulfillApi.courierList()
  }
  catch (error) {
    errorMessage.value
      = error instanceof ContractError ? error.message : '任务列表加载失败，请稍后重试'
  }
  finally {
    loading.value = false
  }
}

onShow(loadTasks)

function openDetail(orderNo: string) {
  goTo('M08', { orderNo })
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="商城配送任务" back-to="U01" />

    <view v-if="loading" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, { width: '60%' }]" />
    </view>
    <view v-else-if="errorMessage" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain size="small" @click="loadTasks">
            重试
          </wd-button>
        </template>
      </AppPageState>
    </view>
    <view v-else-if="tasks.length === 0" class="page-section">
      <AppPageState state="empty" message="暂无分配给你的商城任务" />
    </view>

    <view v-else class="list page-section">
      <view
        v-for="task in tasks"
        :key="task.orderNo"
        class="card surface-card pressable"
        @click="openDetail(task.orderNo)"
      >
        <view class="card__head">
          <text class="card__status">
            {{ task.fulfillStatusName }}
          </text>
          <wd-tag v-if="task.exchangeReshipment" type="warning" plain>
            换货补发
          </wd-tag>
        </view>
        <view class="card__route">
          {{ task.receiverRegion }}
        </view>
        <view class="card__addr">
          {{ task.receiverAddress }}
        </view>
        <view class="card__meta">
          {{ task.warehouseName || '前置仓' }} 发货 · 分配于 {{ formatBizTimeShort(task.assignTime) }}
        </view>
        <view class="card__no num">
          {{ task.orderNo }}
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.state {
  padding: var(--sp-6) var(--sp-4);
  color: var(--app-text-secondary);
  font-size: var(--fs-body);
  text-align: center;

  &--error {
    color: var(--app-color-danger);
  }
}

.card {
  margin-bottom: var(--gap-block);

  &__head {
    display: flex;
    gap: var(--sp-2);
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--sp-2);
  }

  // 地址是配送员最需要一眼看到的信息，任务号排到最后一行作为查证据用的辅助信息
  &__no {
    margin-top: var(--sp-1);
    color: var(--app-text-tertiary);
    font-size: var(--fs-note);
  }

  &__status {
    color: var(--app-color-primary);
    font-size: var(--fs-caption);
    font-weight: 600;
  }

  &__route {
    font-size: var(--fs-title);
    font-weight: 600;
  }

  &__addr {
    margin-top: var(--sp-1);
    color: var(--app-text-secondary);
    font-size: var(--fs-body);
  }

  &__meta {
    margin-top: var(--sp-2);
    color: var(--app-text-tertiary);
    font-size: var(--fs-note);
  }
}
</style>
