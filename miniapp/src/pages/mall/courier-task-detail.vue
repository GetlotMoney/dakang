<script setup lang="ts">
import type { MallFulfill } from '@/api/mall-fulfillment'
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { ContractError } from '@/api/common'
import { mallFulfillApi } from '@/api/mall-fulfillment'
import AppBottomActionBar from '@/components/app-bottom-action-bar.vue'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import BizTimeline from '@/components/biz-timeline.vue'
import { formatBizTimeShort } from '@/utils/format'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '任务详情',
  },
})

const orderNo = ref('')
const loading = ref(true)
const acting = ref(false)
const errorMessage = ref('')
const task = ref<MallFulfill | null>(null)

/** 待取货：可以取货。 */
/** 履约节点转时间线条目：只重排已有字段，不补节点、不推导状态。 */
const timelineNodes = computed(() =>
  (task.value?.timeline ?? []).map(node => ({
    title: node.traceNodeName,
    time: formatBizTimeShort(node.traceTime),
    description: node.traceText,
    actor: node.actorTypeName,
  })),
)

const canFetch = computed(() => task.value?.fulfillStatus === 4)
/** 配送中：可以送达。签收不在配送端——那是用户的动作。 */
const canArrive = computed(() => task.value?.fulfillStatus === 5)

onLoad((query) => {
  orderNo.value = typeof query?.orderNo === 'string' ? query.orderNo : ''
  loadDetail()
})

async function loadDetail() {
  if (!orderNo.value) {
    errorMessage.value = '缺少订单号'
    loading.value = false
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    task.value = await mallFulfillApi.courierDetail(orderNo.value)
  }
  catch (error) {
    // 非本人任务、分配证据不一致都会落到这里：一律不显示收货信息
    task.value = null
    errorMessage.value
      = error instanceof ContractError ? error.message : '任务详情加载失败'
  }
  finally {
    loading.value = false
  }
}

async function runAction(action: () => Promise<MallFulfill>, done: string) {
  if (acting.value) {
    return
  }
  acting.value = true
  try {
    task.value = await action()
    uni.showToast({ title: done, icon: 'success' })
  }
  catch (error) {
    uni.showToast({
      title: error instanceof ContractError ? error.message : '操作失败',
      icon: 'none',
    })
  }
  finally {
    acting.value = false
  }
}

const handleFetch = () => runAction(() => mallFulfillApi.courierFetch(orderNo.value), '已取货')
const handleArrive = () => runAction(() => mallFulfillApi.courierArrive(orderNo.value), '已送达')
</script>

<template>
  <view class="page-shell" :class="{ 'page-shell--with-bar': canFetch || canArrive }">
    <AppNavbar title="任务详情" back-to="M07" />

    <view v-if="loading" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, 1, { width: '70%' }]" />
    </view>
    <view v-else-if="errorMessage" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain size="small" @click="loadDetail">
            重试
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else-if="task">
      <view class="page-section surface-card">
        <view class="section-title-row">
          <view class="section-title">
            {{ task.orderNo }}
          </view>
        </view>
        <view class="row">
          <text class="row__label">
            当前状态
          </text><text>{{ task.fulfillStatusName }}</text>
        </view>
        <wd-notice-bar
          v-if="task.exchangeReshipment"
          type="warning"
          text="换货补发单：用户已付过款，送达后正常签收，不要再向用户收取任何费用。"
          :scrollable="false"
        />
        <view class="row">
          <text class="row__label">
            前置仓
          </text><text>{{ task.warehouseName || '-' }}</text>
        </view>
        <view class="row">
          <text class="row__label">
            收货人
          </text>
          <text>{{ task.receiverName }} {{ task.receiverPhone }}</text>
        </view>
        <view class="row">
          <text class="row__label">
            收货地址
          </text>
          <text>{{ task.receiverRegion }} {{ task.receiverAddress }}</text>
        </view>
      </view>

      <view class="page-section surface-card">
        <view class="section-title-row">
          <view class="section-title">
            履约进度
          </view>
        </view>
        <BizTimeline v-if="timelineNodes.length" :nodes="timelineNodes" />
        <AppPageState
          v-else
          state="error"
          title="履约轨迹缺失"
          message="请联系运营人员核查"
        />
      </view>

      <AppBottomActionBar v-if="canFetch || canArrive">
        <template #summary>
          <text class="muted-text">
            {{ task.fulfillStatusName }}
          </text>
        </template>
        <template #primary>
          <wd-button v-if="canFetch" type="primary" :loading="acting" @click="handleFetch">
            确认取货
          </wd-button>
          <wd-button v-else type="primary" :loading="acting" @click="handleArrive">
            确认送达
          </wd-button>
        </template>
      </AppBottomActionBar>
      <view v-else class="page-section actions__hint">
        {{ task.fulfillStatus >= 6 ? '已送达，等待用户签收' : '当前状态无需配送员操作' }}
      </view>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.row {
  display: flex;
  gap: var(--sp-4);
  justify-content: space-between;
  margin-top: var(--sp-3);
  font-size: var(--fs-body);

  &__label {
    flex: none;
    color: var(--app-text-secondary);
  }
}

.actions {
  &__hint {
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
    text-align: center;
  }
}
</style>
