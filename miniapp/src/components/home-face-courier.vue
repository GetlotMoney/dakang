<script setup lang="ts">
import type { DeliveryTask } from '@/api/delivery'
import { computed, ref, watch } from 'vue'
import { ContractError } from '@/api/common'
import { deliveryApi } from '@/api/delivery'
import { useAccountStore } from '@/store/account'
import { TASK_STATUS_LABELS, TASK_STATUS_TONES } from '@/utils/format'
import { goTo } from '@/utils/navigation'

/** 配送工作态：U01 的配送员视角（主态自适应三张脸之一）。 */
const props = defineProps<{ refreshTick: number }>()
const emit = defineEmits<{ switchFace: [face: 'life'] }>()

const accountStore = useAccountStore()

/**
 * 服务范围展示：Mock 会话上下文自带 courierScope；real 会话只含能力清单，
 * 范围从准入接口读取（同一 ws_courier 事实源）。仅供展示，接单范围仍由服务端强制。
 */
const admissionScope = ref<{ stationIds: string[], serviceRegion?: string } | null>(null)
const scope = computed(() => accountStore.context?.courierScope ?? admissionScope.value)

const loading = ref(true)
const blockedReason = ref('')
const availableTasks = ref<DeliveryTask[]>([])
const activeTasks = ref<DeliveryTask[]>([])

/** 常驻任务条：取最接近完成的进行中任务（4 待签收 > 3 配送中 > 2 已接单），申诉中不进条。 */
const stripTask = computed(() => {
  for (const status of [4, 3, 2]) {
    const task = activeTasks.value.find(item => item.taskStatus === status)
    if (task) {
      return task
    }
  }
  return null
})

const STRIP_ACTIONS: Record<number, string> = {
  2: '去离站',
  3: '去送达',
  4: '去签收',
}

const workingCount = computed(
  () => activeTasks.value.filter(item => [2, 3, 4].includes(item.taskStatus)).length,
)
const appealingCount = computed(
  () => activeTasks.value.filter(item => item.taskStatus === 7).length,
)

watch(() => props.refreshTick, refresh, { immediate: true })

async function refresh() {
  loading.value = true
  blockedReason.value = ''
  try {
    if (!accountStore.context?.courierScope && !admissionScope.value) {
      // 只读展示回退：准入查询失败不阻断任务数据（范围校验在服务端）
      const admission = await deliveryApi.getCourierAdmission().catch(() => null)
      if (admission) {
        admissionScope.value = {
          stationIds: admission.requestedStationIds,
          serviceRegion: admission.requestedRegion,
        }
      }
    }
    const [available, active] = await Promise.all([
      deliveryApi.listTasks('available'),
      deliveryApi.listTasks('active'),
    ])
    availableTasks.value = available
    activeTasks.value = active
  }
  catch (error) {
    availableTasks.value = []
    activeTasks.value = []
    blockedReason.value = error instanceof ContractError ? error.message : '配送数据暂不可用'
  }
  finally {
    loading.value = false
  }
}
</script>

<template>
  <view>
    <view class="page-section">
      <wd-card custom-class="home-card">
        <template #title>
          <view class="face-title-row">
            <view>配送服务范围</view>
            <wd-tag type="success" plain>
              已启用
            </wd-tag>
          </view>
        </template>
        <view class="muted-text">
          {{ scope?.serviceRegion || '服务区域待配置' }} · {{ scope?.stationIds.length ?? 0 }} 个水站
        </view>
        <view v-if="blockedReason" class="face-blocked">
          {{ blockedReason }}
        </view>
      </wd-card>
    </view>

    <view v-if="stripTask" class="page-section">
      <view class="task-strip" @click="goTo('D03', { taskNo: stripTask.taskNo })">
        <view class="task-strip-main">
          <view class="task-strip-title">
            <wd-tag :type="TASK_STATUS_TONES[stripTask.taskStatus]">
              {{ TASK_STATUS_LABELS[stripTask.taskStatus] }}
            </wd-tag>
            <view class="task-strip-no">
              {{ stripTask.taskNo }}
            </view>
          </view>
          <view class="task-strip-address">
            {{ stripTask.receiveAddress }}
          </view>
        </view>
        <view class="task-strip-action">
          {{ STRIP_ACTIONS[stripTask.taskStatus] ?? '查看' }}
          <wd-icon name="arrow-right" size="14px" />
        </view>
      </view>
    </view>

    <view class="page-section">
      <wd-card title="工作概览" custom-class="home-card">
        <view class="work-metrics">
          <view class="work-metric" @click="goTo('D01', { view: 'available' })">
            <view class="work-metric-value">
              {{ availableTasks.length }}
            </view>
            <view class="muted-text">
              待接单
            </view>
          </view>
          <view class="work-metric" @click="goTo('D01', { view: 'active' })">
            <view class="work-metric-value">
              {{ workingCount }}
            </view>
            <view class="muted-text">
              进行中
            </view>
          </view>
          <view class="work-metric" @click="goTo('D01', { view: 'active' })">
            <view class="work-metric-value">
              {{ appealingCount }}
            </view>
            <view class="muted-text">
              申诉中
            </view>
          </view>
        </view>
        <view v-if="loading" class="muted-text">
          加载中…
        </view>
      </wd-card>
    </view>

    <view class="page-section">
      <wd-button block size="large" icon="goods" @click="goTo('D01')">
        进入任务中心
      </wd-button>
    </view>

    <view class="page-section">
      <wd-cell-group border>
        <wd-cell
          title="准入与服务范围"
          icon="secured"
          is-link
          @click="goTo('D02')"
        />
        <wd-cell
          title="生活用水服务"
          label="扫码取水 · 水卡 · 配送订水"
          icon="user"
          is-link
          @click="emit('switchFace', 'life')"
        />
      </wd-cell-group>
    </view>
  </view>
</template>

<style scoped lang="scss">
.face-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.face-blocked {
  margin-top: 8px;
  color: var(--app-color-danger);
  font-size: 13px;
}

.task-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-radius: 12px;
  background: rgba(93, 135, 255, 0.1);
  border: 1px solid rgba(93, 135, 255, 0.28);
}

.task-strip-main {
  min-width: 0;
  padding-right: 12px;
}

.task-strip-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-strip-no {
  font-size: 15px;
  font-weight: 600;
}

.task-strip-address {
  margin-top: 6px;
  overflow: hidden;
  color: var(--app-text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-strip-action {
  display: flex;
  align-items: center;
  gap: 2px;
  color: var(--wot-color-theme, var(--app-color-primary));
  font-size: 14px;
  font-weight: 600;
  flex-shrink: 0;
}

.work-metrics {
  display: flex;
  align-items: center;
  justify-content: space-around;
  padding: 4px 0;
}

.work-metric {
  text-align: center;
}

.work-metric-value {
  font-size: 22px;
  font-weight: 600;
}
</style>
