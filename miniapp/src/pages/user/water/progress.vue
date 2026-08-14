<script setup lang="ts">
import type { OrderDetail, OrderTraceNode } from '@/api/order'
import type { TagTone } from '@/utils/format'
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { ContractError } from '@/api/common'
import { orderApi } from '@/api/order'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import {
  COMMAND_STATUS_LABELS,
  formatBizTime,
  formatFen,
  formatMl,
  ORDER_STATUS_LABELS,
  ORDER_STATUS_TONES,
  PAY_WAY_LABELS,
} from '@/utils/format'
import { backOr, redirectTo, switchToTab } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '取水进度',
  },
})

/**
 * 状态大字的色调，与 wd-tag 同一口径。
 *
 * <p>此处只产出 tone 名，由样式表把 tone 映射到 token；曾经在这里直接存五个十六进制值，
 * 那五个值抄的是 Wot 1.14.0 组件默认色，主题变量一改就与相邻 tag 脱节。</p>
 */
const TONE_CLASS: Record<TagTone, string> = {
  default: 'tone-default',
  primary: 'tone-primary',
  danger: 'tone-danger',
  warning: 'tone-warning',
  success: 'tone-success',
}

/** 订单终态集合；出水中(3)等待设备回传，不算终态。 */
const TERMINAL_STATUSES = [4, 5, 6, 7, 8]

const pageState = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')

const detail = ref<OrderDetail | null>(null)

// 轨迹一次性全量展示。这里曾有一套 700ms 逐节点回放，只对 evidenceMode=prototype 的
// 原型单生效——而真实订单从不携带 mockMeta，那条分支在任何出货构建里都进不去，
// 页面实际一直走的就是「直接显示全部」。留着它的代价是三个恒定值（visibleTrace===trace、
// playbackDone===true、activeIndex 的前半支永不取到）伪装成状态机，读代码的人会以为有时序。
const trace = computed(() => detail.value?.trace ?? [])

const isTerminal = computed(() =>
  !!detail.value && TERMINAL_STATUSES.includes(detail.value.order.orderStatus),
)

const activeIndex = computed(() =>
  isTerminal.value ? trace.value.length : trace.value.length - 1,
)

const statusToneClass = computed(() => {
  if (!detail.value) {
    return TONE_CLASS.default
  }
  return TONE_CLASS[ORDER_STATUS_TONES[detail.value.order.orderStatus]]
})

/** 异常(6)/取消(5) 时从 trace 提取原因原位展示。 */
const abnormalReason = computed(() => {
  const data = detail.value
  if (!data || ![5, 6].includes(data.order.orderStatus)) {
    return ''
  }
  const node = [...data.trace].reverse().find(item => item.tone === 'danger' || item.tone === 'warning')
  if (!node) {
    return ''
  }
  return node.detail ? `${node.label}：${node.detail}` : node.label
})

/** trace 色调 → wd-steps 状态映射（steps 仅支持 finished/process/error）。 */
function stepStatus(node: OrderTraceNode): 'finished' | 'error' | undefined {
  if (node.tone === 'danger' || node.tone === 'warning') {
    return 'error'
  }
  if (node.tone === 'success') {
    return 'finished'
  }
  return undefined
}

onLoad((query?: Record<string, string | undefined>) => {
  const orderNo = query?.orderNo
  if (!orderNo) {
    pageState.value = 'error'
    errorMessage.value = '请从订单列表进入'
    return
  }
  void load(orderNo)
})

async function load(orderNo: string) {
  pageState.value = 'loading'
  try {
    detail.value = await orderApi.getOrderDetail(orderNo)
    pageState.value = 'ready'
  }
  catch (error) {
    pageState.value = 'error'
    errorMessage.value = error instanceof ContractError ? error.message : '取水进度加载失败，请重试'
  }
}

function goDetail() {
  if (detail.value) {
    redirectTo('U06', { orderNo: detail.value.order.orderNo })
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="取水进度" back-to="U01" />

    <view v-if="pageState === 'loading'" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, 1, { width: '70%' }]" />
    </view>

    <view v-else-if="pageState === 'error'" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain @click="backOr('U01')">
            返回首页
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else-if="detail">
      <view class="page-section">
        <wd-card custom-class="block-card">
          <!-- 全页唯一主视觉：实际出水量。原来这张卡只放一个状态词，
               而「到底出了多少水」被压在下面订单信息里当第 5 行 14px 灰字，
               与「计划水量」「订单号」同权重——最该看的反而最不显眼。 -->
          <view class="progress-status">
            <view class="progress-status-text" :class="statusToneClass">
              {{ ORDER_STATUS_LABELS[detail.order.orderStatus] }}
            </view>
            <view v-if="detail.order.orderType === 1" class="progress-volume">
              <text class="progress-volume__value num">
                {{ detail.order.actualMl !== undefined ? formatMl(detail.order.actualMl) : '—' }}
              </text>
              <text class="progress-volume__label">
                实际出水{{ detail.order.planMl !== undefined ? ` · 计划 ${formatMl(detail.order.planMl)}` : '' }}
              </text>
            </view>
            <view v-if="abnormalReason" class="progress-abnormal">
              {{ abnormalReason }}
            </view>
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card title="执行轨迹" custom-class="block-card">
          <wd-steps v-if="trace.length" :active="activeIndex" vertical>
            <wd-step
              v-for="(node, index) in trace"
              :key="`${node.node}-${index}`"
              :title="node.label"
              :status="stepStatus(node)"
            >
              <template #description>
                <view class="step-desc">
                  <view>{{ formatBizTime(node.time) }}</view>
                  <view v-if="node.detail" class="muted-text">
                    {{ node.detail }}
                  </view>
                </view>
              </template>
            </wd-step>
          </wd-steps>
          <view v-else class="muted-text">
            暂无执行轨迹记录。
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card title="订单信息" custom-class="block-card">
          <wd-cell-group>
            <wd-cell title="订单号" :value="detail.order.orderNo" ellipsis />
            <!-- 原来这里摆的是「命令号」——内部下发指令的主键，用户拿它做不了任何决定；
                 而真正的事实「指令有没有下发成功」页面一个字都没说。换成指令状态。 -->
            <wd-cell
              v-if="detail.commandStatus !== undefined"
              title="指令状态"
              :value="COMMAND_STATUS_LABELS[detail.commandStatus] ?? '—'"
            />
            <wd-cell title="站点" :value="detail.order.stationName ?? '—'" ellipsis />
            <wd-cell title="设备" :value="detail.order.deviceNo ?? '—'" ellipsis />
            <wd-cell title="金额" :value="formatFen(detail.order.orderAmountFen)" />
            <wd-cell title="支付方式" :value="PAY_WAY_LABELS[detail.order.payWay]" />
          </wd-cell-group>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-button v-if="isTerminal" block size="large" @click="goDetail">
          查看订单详情
        </wd-button>
        <template v-else>
          <wd-button block plain size="large" @click="switchToTab('U02')">
            稍后在订单列表查看
          </wd-button>
        </template>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.progress-status {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 4px 0;
}

.progress-status-text {
  font-size: var(--fs-title);
  font-weight: 700;
}

// 本页唯一的 display 字号：实际出水量。计划量作为它的对照小字跟在下面。
.progress-volume {
  margin-top: var(--sp-2);

  &__value {
    display: block;
    font-size: var(--fs-display);
    font-weight: 700;
    line-height: 1.1;
  }

  &__label {
    display: block;
    margin-top: var(--sp-1);
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
  }
}

// 异常原因与状态同档语义色、正文字号：它是用户要据以行动的那句话，
// 不该和「计划水量」共用一档 12px 灰字
.progress-abnormal {
  margin-top: var(--sp-2);
  color: var(--app-color-danger);
  font-size: var(--fs-body);
}

.tone-default {
  color: var(--app-text-secondary);
}

.tone-primary {
  color: var(--app-color-primary);
}

.tone-danger {
  color: var(--app-color-danger);
}

.tone-warning {
  color: var(--app-color-warning-text);
}

.tone-success {
  color: var(--app-color-success);
}

.step-desc {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}
</style>
