<script setup lang="ts">
import type { DeliveryTask } from '@/api/delivery'
import AppPageState from '@/components/app-page-state.vue'
import { computed, ref, watch } from 'vue'
import { ContractError } from '@/api/common'
import { deliveryApi } from '@/api/delivery'
import { useAccountStore } from '@/store/account'
import { formatBizTimeShort, TASK_STATUS_LABELS, TASK_STATUS_TONES } from '@/utils/format'
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
const scopeLabel = computed(
  () => `${scope.value?.serviceRegion || '服务区域待配置'} · ${scope.value?.stationIds.length ?? 0} 个水站`,
)

const loading = ref(true)
const blockedReason = ref('')
const availableTasks = ref<DeliveryTask[]>([])
const activeTasks = ref<DeliveryTask[]>([])

/** 常驻任务条：取最接近完成的进行中任务（4 待签收 > 3 配送中 > 2 已接单），申诉中不进条。 */
const currentTask = computed(() => {
  for (const status of [4, 3, 2]) {
    const task = activeTasks.value.find(item => item.taskStatus === status)
    if (task) {
      return task
    }
  }
  return null
})

/** 一单只给一个下一步动作，避免工作台上出现两个同权按钮。 */
const NEXT_ACTIONS: Record<number, string> = {
  2: '去离站',
  3: '去送达',
  4: '去签收',
}

/**
 * 任务条第二行的时间。
 *
 * <p>配送任务快照上没有「预约时间」这个字段，只有履约过程中真实落下的接单/离站/到达
 * 时间戳。按当前状态取对应的那一个并写明它是什么时间，不拿它冒充预约时段——
 * 配送员据此决定先跑哪一单，标错比不标更糟。</p>
 */
const currentTaskTime = computed(() => {
  const task = currentTask.value
  if (!task) {
    return ''
  }
  const picked
    = task.taskStatus === 2
      ? { label: '接单', time: task.acceptTime }
      : task.taskStatus === 3
        ? { label: '离站', time: task.departTime }
        : { label: '到达', time: task.arriveTime }
  const text = formatBizTimeShort(picked.time)
  return text === '—' ? '' : `${picked.label} ${text}`
})

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
    <!-- 工作台的第一主信息是「下一步去哪儿」：地址占最大字号，状态与履约时间第二，
         任务号退到最弱一档。整条只给一个下一步动作。 -->
    <view v-if="currentTask" class="current pressable" @click="goTo('D03', { taskNo: currentTask.taskNo })">
      <view class="current__meta">
        <wd-tag :type="TASK_STATUS_TONES[currentTask.taskStatus]">
          {{ TASK_STATUS_LABELS[currentTask.taskStatus] }}
        </wd-tag>
        <text v-if="currentTaskTime" class="current__time">
          {{ currentTaskTime }}
        </text>
      </view>
      <text class="current__address">
        {{ currentTask.receiveAddress }}
      </text>
      <view class="current__foot">
        <text class="current__no">
          {{ currentTask.taskNo }}
        </text>
        <view class="current__action">
          {{ NEXT_ACTIONS[currentTask.taskStatus] ?? '查看' }}
          <wd-icon name="arrow-right" size="14px" />
        </view>
      </view>
    </view>

    <!-- 工作量：三个计数分栏，用中性竖线分隔，不各包一张卡 -->
    <view class="board">
      <view class="board__head">
        <text class="board__title">
          工作概览
        </text>
        <wd-tag type="success" plain>
          已启用
        </wd-tag>
      </view>
      <!-- 服务范围是配送员的作业上下文，放在计数上方一行说完。
           原来它是「准入与服务范围」那条 wd-cell 的 label，被 cell 的窄栏挤成
           「武汉东湖高新区 · 2 个水 / 站」——断在分隔点后面，读起来像坏掉的文本。 -->
      <text class="board__scope">
        {{ scopeLabel }}
      </text>
      <!-- 取数失败时不渲染计数：清零后的 0/0/0 与「真的没有任务」长得一模一样，
           把失败伪装成空态会让配送员以为今天没活干。失败只显示原因。 -->
      <view v-if="!blockedReason" class="board__counts">
        <view class="board__count pressable" @click="goTo('D01', { view: 'available' })">
          <text class="board__value num">
            {{ availableTasks.length }}
          </text>
          <text class="board__label">
            待接单
          </text>
        </view>
        <view class="board__count pressable" @click="goTo('D01', { view: 'active' })">
          <text class="board__value num">
            {{ workingCount }}
          </text>
          <text class="board__label">
            进行中
          </text>
        </view>
        <view class="board__count pressable" @click="goTo('D01', { view: 'active' })">
          <text class="board__value num" :class="{ 'board__value--warn': appealingCount > 0 }">
            {{ appealingCount }}
          </text>
          <text class="board__label">
            申诉中
          </text>
        </view>
      </view>
      <view v-if="blockedReason" class="board__blocked">
        {{ blockedReason }}
      </view>
      <AppPageState v-else-if="loading" state="loading" :row-col="[1, { width: '60%' }]" />
    </view>

    <view class="enter">
      <wd-button block size="large" icon="goods" @click="goTo('D01')">
        进入任务中心
      </wd-button>
    </view>

    <view class="nav">
      <wd-cell-group border>
        <wd-cell
          title="准入与服务范围"
          icon="secured"
          is-link
          @click="goTo('D02')"
        />
        <!-- 不给「生活用水服务」挂 label：那句「扫码取水 · 水卡 · 配送订水」是在解释
             标题里装了什么，标题本身已经说清，删掉顺带消除 cell 窄栏折行。 -->
        <wd-cell
          title="生活用水服务"
          icon="user"
          is-link
          @click="emit('switchFace', 'life')"
        />
      </wd-cell-group>
    </view>
  </view>
</template>

<style scoped lang="scss">
.current {
  margin-top: var(--gap-hero);
  padding: var(--sp-4);
  border-radius: var(--r-md);
  background: var(--app-bg-card);

  &__meta {
    display: flex;
    gap: var(--sp-2);
    align-items: center;
  }

  &__time {
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
  }

  &__address {
    display: block;
    margin-top: var(--sp-2);
    font-size: var(--fs-title);
    font-weight: 700;
    line-height: 1.35;
  }

  &__foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: var(--sp-3);
    padding-top: var(--sp-3);
    border-top: 1px solid var(--line-1);
  }

  &__no {
    overflow: hidden;
    color: var(--app-text-tertiary);
    font-size: var(--fs-note);
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__action {
    display: flex;
    flex: none;
    align-items: center;
    gap: 2px;
    color: var(--app-color-primary);
    font-size: var(--fs-caption);
    font-weight: 600;
  }
}

.board {
  margin-top: var(--gap-group);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__title {
    font-size: var(--fs-title);
    font-weight: 600;
  }

  &__scope {
    display: block;
    overflow: hidden;
    margin-top: var(--sp-1);
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__counts {
    display: flex;
    margin-top: var(--sp-3);
  }

  &__count {
    flex: 1;
    min-width: 0;
    text-align: center;

    & + & {
      border-left: 1px solid var(--line-1);
    }
  }

  &__value {
    display: block;
    font-size: var(--fs-metric);
    font-weight: 700;

    &--warn {
      color: var(--app-color-warning-text);
    }
  }

  &__label {
    display: block;
    margin-top: var(--sp-1);
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
  }

  &__blocked {
    margin-top: var(--sp-3);
    color: var(--app-color-danger);
    font-size: var(--fs-caption);
  }
}

.enter {
  margin-top: var(--gap-group);
}

.nav {
  margin-top: var(--gap-block);
  overflow: hidden;
  border-radius: var(--r-md);
}
</style>
