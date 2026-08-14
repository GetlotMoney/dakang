<script setup lang="ts">
import type { DeliveryTask, DeliveryTaskView } from '@/api/delivery'
import AppPageState from '@/components/app-page-state.vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { ContractError } from '@/api/common'
import { deliveryApi, deliveryTotalText } from '@/api/delivery'
import AppNavbar from '@/components/app-navbar.vue'
import { useAccountStore } from '@/store/account'
import {
  ADMISSION_STATUS_LABELS,
  ADMISSION_STATUS_TONES,
  formatBizTimeShort,
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
  { name: 'available', title: '待接任务', emptyTip: '暂无可接任务' },
  { name: 'active', title: '进行中', emptyTip: '暂无进行中任务' },
  { name: 'history', title: '已完成', emptyTip: '暂无已完成任务' },
] as const

const accountStore = useAccountStore()

/**
 * 服务范围展示仅供导航参考：范围从准入接口读取（同一 ws_courier 事实源），
 * 接单时服务端仍按会话强制解析范围（铁律6/7）。
 */
const admissionScope = ref<{ status: 0 | 1 | 2 | 3 | 4, stationIds: string[], serviceRegion?: string } | null>(null)
const courierScope = computed(() => accountStore.context?.courierScope ?? admissionScope.value)

const view = ref<DeliveryTaskView>('available')
const status = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
const tasks = ref<DeliveryTask[]>([])
const exceptionCounts = ref<Record<string, number>>({})
// 重复请求去重：只采纳最后一次查询结果。
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

/** 能力投影只决定这一屏渲染哪种状态，最终授权在服务端；无 COURIER_WORK 是 blocked，不是加载失败。 */
const hasCourierWork = computed(() => accountStore.hasCapability('COURIER_WORK'))

onShow(refresh)

async function refresh() {
  if (!accountStore.restored) {
    await accountStore.restoreSession().catch(() => null)
  }
  if (!hasCourierWork.value) {
    status.value = 'ready'
    tasks.value = []
    return
  }
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
/** 列表行尾的下一步文案。只做展示不承载动作——守卫、乐观锁与二次确认全在任务详情页。 */
const NEXT_STEP_LABELS: Record<number, string> = {
  1: '去接单',
  2: '去离站',
  3: '去送达',
  4: '去签收',
}

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

    <!-- 无接单资格：blocked 而不是 error，给「去申请准入」这个去处 -->
    <view v-if="!hasCourierWork" class="page-section">
      <AppPageState state="blocked" title="当前账号未开通配送接单">
        <template #actions>
          <wd-button plain @click="goTo('D02')">
            去申请准入
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else>
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
              <wd-icon name="location" size="14px" custom-class="scope-icon" />
              <view class="scope-text">
                {{ courierScope.serviceRegion || '服务区域未配置' }} · {{ courierScope.stationIds.length }} 个水站
              </view>
            </view>
          </view>
          <!-- 结论 + 去处，不复述空列表 -->
          <view v-else class="scope-empty">
            <text class="muted-text">
              未配置服务范围
            </text>
            <wd-button size="small" plain @click="goTo('D02')">
              去申请准入
            </wd-button>
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
        <AppPageState state="loading" :row-col="[1, 1, { width: '60%' }]" />
      </view>

      <view v-else-if="status === 'error'" class="page-section">
        <AppPageState state="error" :message="errorMessage" />
      </view>

      <template v-else>
        <view v-if="tasks.length" class="page-section">
          <view
            v-for="task in tasks"
            :key="task.taskNo"
            class="task-card pressable"
            @click="goTo('D03', { taskNo: task.taskNo })"
          >
            <!-- 第一主信息是「送到哪儿」：地址置顶，任务号退到行尾最弱一档 -->
            <view class="task-card-address">
              {{ task.receiveAddress }}
            </view>
            <view class="task-card-status">
              <!-- 补送任务标识（E2E-04 包C）：只在服务端标明时出现，不按零金额推断 -->
              <wd-tag v-if="task.isResend" type="primary" plain>
                补送
              </wd-tag>
              <wd-tag :type="TASK_STATUS_TONES[task.taskStatus]" plain>
                {{ TASK_STATUS_LABELS[task.taskStatus] }}
              </wd-tag>
              <text class="task-card-time">
                {{ latestNodeText(task) }}
              </text>
              <wd-tag v-if="exceptionCounts[task.taskNo]" type="warning" plain icon="warning">
                异常 {{ exceptionCounts[task.taskNo] }}
              </wd-tag>
            </view>
            <view class="task-card-line">
              {{ task.waterTypeName }} · {{ task.containerSpec }}×{{ task.plannedDeliveryCount }} · 预计回收 {{ task.plannedReturnCount }}
            </view>
            <view class="task-card-footer">
              <!-- D-214：payWay=3 呈现「¥配送费+抵扣升数」，不把只剩配送费的金额当全部对价 -->
              <view class="task-card-amount money">
                {{ deliveryTotalText(task) }}
              </view>
              <text class="task-card-no">
                {{ task.taskNo }}
              </text>
            </view>
            <!-- 一单只给一个下一步：这里只是标签，真正的动作与守卫都在任务详情里 -->
            <view class="task-card-next">
              {{ NEXT_STEP_LABELS[task.taskStatus] ?? '查看详情' }}
              <wd-icon name="arrow-right" size="14px" />
            </view>
          </view>
        </view>
        <view v-else class="page-section">
          <AppPageState state="empty" :message="emptyTip" />
        </view>
      </template>

      <!-- 商城配送任务与水配送任务分属两条链（任务表/状态/共键都不同），此处只做跳转不混列——混列会让 S4 售后按任务检索时串单 -->
      <view class="page-section mall-entry">
        <wd-button size="small" plain @click="goTo('M07')">
          商城配送任务
        </wd-button>
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
  background: var(--app-bg-card);

  & + .task-card {
    margin-top: 12px;
  }
}

// 地址是行内主信息：最多两行，超出省略——配送员一眼要看到送到哪儿，
// 而完整地址在详情页有。
.task-card-address {
  display: -webkit-box;
  overflow: hidden;
  font-size: var(--fs-title);
  font-weight: 700;
  line-height: 1.35;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.task-card-status {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  align-items: center;
  margin-top: var(--sp-2);
}

.task-card-time {
  color: var(--app-text-secondary);
  font-size: var(--fs-note);
}

.task-card-line {
  margin-top: var(--sp-2);
  color: var(--app-text-secondary);
  font-size: var(--fs-caption);
}

.task-card-footer {
  display: flex;
  gap: var(--sp-3);
  align-items: baseline;
  justify-content: space-between;
  margin-top: var(--sp-2);
}

.task-card-amount {
  flex: none;
  font-size: var(--fs-title);
  font-weight: 700;
}

// 任务号退到最弱一档：它是报障时才用得上的查证串，不参与「先跑哪一单」的判断
.task-card-no {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--app-text-tertiary);
  font-size: var(--fs-note);
  text-align: right;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.scope-empty {
  display: flex;
  gap: var(--sp-3);
  align-items: center;
  justify-content: space-between;
}

.scope-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.task-card-next {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  margin-top: var(--sp-3);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--line-1);
  color: var(--app-color-primary);
  font-size: var(--fs-caption);
  font-weight: 600;
}

.mall-entry {
  display: flex;
  justify-content: flex-end;
}
</style>
