<script setup lang="ts">
import type { DeliveryTask, DeliveryTaskView } from '@/api/delivery'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { ContractError } from '@/api/common'
import { deliveryApi } from '@/api/delivery'
import AppNavbar from '@/components/app-navbar.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import { useAccountStore } from '@/store/account'
import {
  ADMISSION_STATUS_LABELS,
  ADMISSION_STATUS_TONES,
  formatBizTimeShort,
  formatFen,
  TASK_STATUS_LABELS,
  TASK_STATUS_TONES,
} from '@/utils/format'
import { goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '配送任务中心',
  },
})

const VIEW_OPTIONS = [
  { name: 'available', title: '待接任务', emptyTip: '暂无可接任务（本人下单任务不进入可接列表）' },
  { name: 'active', title: '进行中', emptyTip: '暂无进行中任务' },
  { name: 'history', title: '已完成', emptyTip: '暂无已完成任务' },
] as const

const accountStore = useAccountStore()

/**
 * 服务范围展示：Mock 会话上下文自带 courierScope；real 会话上下文只含能力清单，
 * 范围从准入接口读取（同一 ws_courier 事实源）。展示仅供导航参考，
 * 接单时服务端仍按会话强制解析范围（铁律6/7）。
 */
const admissionScope = ref<{ status: 0 | 1 | 2 | 3 | 4, stationIds: string[], serviceRegion?: string } | null>(null)
const courierScope = computed(() => accountStore.context?.courierScope ?? admissionScope.value)

const view = ref<DeliveryTaskView>('available')
const status = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
const tasks = ref<DeliveryTask[]>([])
const exceptionCounts = ref<Record<string, number>>({})
// 重复请求去重（蓝图 9.5 列表状态）：只采纳最后一次查询结果。
let requestSequence = 0

const emptyTip = computed(
  () => VIEW_OPTIONS.find(item => item.name === view.value)?.emptyTip ?? '暂无任务',
)

onLoad((query) => {
  const value = query?.view
  if (value === 'available' || value === 'active' || value === 'history') {
    view.value = value
  }
})

onShow(refresh)

async function refresh() {
  const sequence = ++requestSequence
  status.value = 'loading'
  errorMessage.value = ''
  try {
    if (!accountStore.restored) {
      await accountStore.restoreSession().catch(() => null)
    }
    if (!accountStore.context?.courierScope && !admissionScope.value) {
      // 只读展示回退：准入查询失败不阻断任务列表（列表自身的范围校验在服务端）
      const admission = await deliveryApi.getCourierAdmission().catch(() => null)
      if (admission) {
        admissionScope.value = {
          status: admission.status,
          stationIds: admission.requestedStationIds,
          serviceRegion: admission.requestedRegion,
        }
      }
    }
    const list = await deliveryApi.listTasks(view.value)
    if (sequence !== requestSequence) {
      return
    }
    tasks.value = list
    await loadExceptionMarks(list, sequence)
    if (sequence === requestSequence) {
      status.value = 'ready'
    }
  }
  catch (error) {
    if (sequence !== requestSequence) {
      return
    }
    tasks.value = []
    // COURIER_SCOPE_DENIED / CAPABILITY_DENIED 等按契约原因原位展示，不伪装为空数据。
    errorMessage.value = error instanceof ContractError ? error.message : '配送任务加载失败'
    status.value = 'error'
  }
}

/** 契约缺口：DeliveryTask 无异常记录聚合字段，列表标记只能逐任务查询异常记录。 */
async function loadExceptionMarks(list: DeliveryTask[], sequence: number) {
  const entries = await Promise.all(
    list.map(async (task) => {
      const records = await deliveryApi.listTaskExceptions(task.taskNo).catch(() => [])
      return [task.taskNo, records.length] as const
    }),
  )
  if (sequence !== requestSequence) {
    return
  }
  exceptionCounts.value = Object.fromEntries(entries)
}

/** 列表时间列：展示最近一个已发生的履约节点（D01：acceptTime/departTime 最近节点口径）。 */
function latestNodeText(task: DeliveryTask) {
  if (task.signTime) {
    return `签收 ${formatBizTimeShort(task.signTime)}`
  }
  if (task.arriveTime) {
    return `送达 ${formatBizTimeShort(task.arriveTime)}`
  }
  if (task.departTime) {
    return `离站 ${formatBizTimeShort(task.departTime)}`
  }
  if (task.acceptTime) {
    return `接单 ${formatBizTimeShort(task.acceptTime)}`
  }
  return '等待接单'
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="配送任务中心" back-to="U01" />
    <AppPrototypeNotice domain="delivery" />

    <view class="page-section">
      <wd-card custom-class="scope-card">
        <template #title>
          <view class="card-title-row">
            <view>服务范围</view>
            <wd-tag
              v-if="courierScope"
              :type="ADMISSION_STATUS_TONES[courierScope.status]"
              plain
            >
              {{ ADMISSION_STATUS_LABELS[courierScope.status] }}
            </wd-tag>
          </view>
        </template>
        <view v-if="courierScope" class="scope-body">
          <view class="scope-line">
            <wd-icon name="location" size="14px" />
            <view>{{ courierScope.serviceRegion || '服务区域未配置' }} · {{ courierScope.stationIds.length }} 个水站</view>
          </view>
          <view class="muted-text">
            本人下单任务不进入可接列表；接单时服务端再次校验范围、归属与任务版本。
          </view>
        </view>
        <view v-else class="muted-text">
          配送范围未配置，默认不可接单。
        </view>
      </wd-card>
    </view>

    <view class="page-section">
      <wd-tabs v-model="view" @change="refresh">
        <wd-tab
          v-for="item in VIEW_OPTIONS"
          :key="item.name"
          :name="item.name"
          :title="item.title"
        />
      </wd-tabs>
    </view>

    <view v-if="status === 'loading'" class="page-section state-block">
      <wd-loading size="24px" />
      <view class="muted-text">
        任务加载中…
      </view>
    </view>

    <view v-else-if="status === 'error'" class="page-section">
      <wd-status-tip image="network" :tip="errorMessage" />
    </view>

    <template v-else>
      <view v-if="tasks.length" class="page-section">
        <view
          v-for="task in tasks"
          :key="task.taskNo"
          class="task-card"
          @click="goTo('D03', { taskNo: task.taskNo })"
        >
          <view class="task-card-header">
            <view class="task-card-no">
              {{ task.taskNo }}
            </view>
            <wd-tag :type="TASK_STATUS_TONES[task.taskStatus]" plain>
              {{ TASK_STATUS_LABELS[task.taskStatus] }}
            </wd-tag>
          </view>
          <view class="task-card-line">
            {{ task.waterTypeName }} · {{ task.containerSpec }}×{{ task.plannedDeliveryCount }} · 预计回收 {{ task.plannedReturnCount }}
          </view>
          <view class="task-card-line task-card-address">
            <wd-icon name="location" size="14px" />
            <view class="task-card-address-text">
              {{ task.receiveAddress }}
            </view>
          </view>
          <view class="task-card-footer">
            <view class="task-card-amount">
              {{ formatFen(task.priceSnapshot.totalAmountFen) }}
            </view>
            <view class="muted-text">
              {{ latestNodeText(task) }}
            </view>
          </view>
          <view v-if="exceptionCounts[task.taskNo]" class="task-card-marks">
            <wd-tag type="warning" plain icon="warning">
              异常记录 {{ exceptionCounts[task.taskNo] }}
            </wd-tag>
          </view>
        </view>
      </view>
      <view v-else class="page-section">
        <wd-status-tip image="content" :tip="emptyTip" />
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

.scope-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.scope-line {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
}

.state-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 0;
}

.task-card {
  padding: 14px;
  border-radius: 8px;
  background: #fff;

  & + .task-card {
    margin-top: 12px;
  }
}

.task-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.task-card-no {
  font-size: 15px;
  font-weight: 600;
}

.task-card-line {
  margin-top: 8px;
  font-size: 13px;
  color: var(--app-text-primary);
}

.task-card-address {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--app-text-secondary);
}

.task-card-address-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}

.task-card-amount {
  font-size: 16px;
  font-weight: 600;
}

.task-card-marks {
  margin-top: 8px;
}
</style>
