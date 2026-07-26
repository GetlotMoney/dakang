<script setup lang="ts">
import type { OrderDetail, OrderTraceNode } from '@/api/order'
import type { TagTone } from '@/utils/format'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { ContractError } from '@/api/common'
import { orderApi } from '@/api/order'
import { currentMode } from '@/api/runtime'
import AppNavbar from '@/components/app-navbar.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import {
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

/** 状态大字用色，与 wd-tag 色调保持同一口径。 */
const TONE_COLORS: Record<TagTone, string> = {
  default: '#646a73',
  primary: '#5d87ff',
  danger: '#fa4350',
  warning: '#f0883a',
  success: '#34d19d',
}

/** 订单终态集合（蓝图 §9.1）；出水中(3)等待设备回传，不算终态。 */
const TERMINAL_STATUSES = [4, 5, 6, 7, 8]

const PLAYBACK_INTERVAL_MS = 700
const isRealWaterFlow = [currentMode('device'), currentMode('order'), currentMode('card')]
  .every(mode => mode === 'real')
const progressNotice = isRealWaterFlow
  ? '取水进度来自真实订单与设备回执；仅在正式微信会话下才构成真实扣款与设备履约证据。'
  : '取水进度为 Mock/固定快照回放：用于验证页面链路，不代表本次发生真实扣款或设备出水。'

const pageState = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
const errorImage = ref<'content' | 'network'>('network')

const detail = ref<OrderDetail | null>(null)
const visibleCount = ref(0)
let playTimer: ReturnType<typeof setInterval> | null = null

const trace = computed(() => detail.value?.trace ?? [])
const visibleTrace = computed(() => trace.value.slice(0, visibleCount.value))
const playbackDone = computed(() => visibleCount.value >= trace.value.length)

const isTerminal = computed(() =>
  !!detail.value && TERMINAL_STATUSES.includes(detail.value.order.orderStatus),
)

const activeIndex = computed(() => {
  if (!playbackDone.value) {
    return visibleCount.value - 1
  }
  return isTerminal.value ? trace.value.length : trace.value.length - 1
})

const statusColor = computed(() => {
  if (!detail.value || !playbackDone.value) {
    return TONE_COLORS.default
  }
  return TONE_COLORS[ORDER_STATUS_TONES[detail.value.order.orderStatus]]
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
    errorImage.value = 'content'
    errorMessage.value = '缺少订单号参数，请从订单列表进入'
    return
  }
  void load(orderNo)
})

onUnload(stopPlayback)

async function load(orderNo: string) {
  pageState.value = 'loading'
  try {
    detail.value = await orderApi.getOrderDetail(orderNo)
    pageState.value = 'ready'
    startPlayback()
  }
  catch (error) {
    pageState.value = 'error'
    errorImage.value = error instanceof ContractError && error.code === 'ORDER_NOT_FOUND'
      ? 'content'
      : 'network'
    errorMessage.value = error instanceof ContractError ? error.message : '取水进度加载失败，请重试'
  }
}

/**
 * 播放策略（蓝图 U05）：新建原型单（evidenceMode=prototype）按 700ms 逐节点回放；
 * 固定快照单直接静态全量展示。数据本身已是终态，回放只是展示层动画，无人工推进。
 */
function startPlayback() {
  const total = trace.value.length
  if (total === 0) {
    visibleCount.value = 0
    return
  }
  if (detail.value?.order.mockMeta?.evidenceMode !== 'prototype') {
    visibleCount.value = total
    return
  }
  visibleCount.value = 1
  playTimer = setInterval(() => {
    if (visibleCount.value >= trace.value.length) {
      stopPlayback()
      return
    }
    visibleCount.value += 1
  }, PLAYBACK_INTERVAL_MS)
}

function stopPlayback() {
  if (playTimer !== null) {
    clearInterval(playTimer)
    playTimer = null
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
    <AppPrototypeNotice :text="progressNotice" />

    <view v-if="pageState === 'loading'" class="page-section loading-box">
      <wd-loading />
      <view class="muted-text">
        正在加载取水进度…
      </view>
    </view>

    <view v-else-if="pageState === 'error'" class="page-section">
      <wd-status-tip :image="errorImage" :tip="errorMessage">
        <template #bottom>
          <view class="status-actions">
            <wd-button plain @click="backOr('U01')">
              返回首页
            </wd-button>
          </view>
        </template>
      </wd-status-tip>
    </view>

    <template v-else-if="detail">
      <view class="page-section">
        <wd-card custom-class="block-card">
          <view class="progress-status">
            <view class="progress-status-text" :style="{ color: statusColor }">
              {{ playbackDone ? ORDER_STATUS_LABELS[detail.order.orderStatus] : '出水流程回放中…' }}
            </view>
            <view v-if="!playbackDone" class="muted-text">
              按固定证据逐节点展示，无人工推进入口
            </view>
            <view v-else-if="detail.order.orderStatus === 3" class="muted-text">
              等待设备回传结果（固定快照，无人工推进）
            </view>
            <view v-else-if="abnormalReason" class="muted-text">
              {{ abnormalReason }}
            </view>
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card title="执行轨迹" custom-class="block-card">
          <wd-steps v-if="visibleTrace.length" :active="activeIndex" vertical>
            <wd-step
              v-for="(node, index) in visibleTrace"
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
            <wd-cell title="订单号" :value="detail.order.orderNo" />
            <wd-cell v-if="detail.commandNo" title="命令号" :value="detail.commandNo" />
            <wd-cell
              title="站点 / 设备"
              :value="`${detail.order.stationName ?? '—'} · ${detail.order.deviceNo ?? '—'}`"
            />
            <wd-cell
              v-if="detail.order.planMl !== undefined"
              title="计划水量"
              :value="formatMl(detail.order.planMl)"
            />
            <wd-cell
              v-if="detail.order.orderType === 1"
              title="实际水量"
              :value="detail.order.actualMl !== undefined ? formatMl(detail.order.actualMl) : '待设备回传'"
            />
            <wd-cell title="金额" :value="formatFen(detail.order.orderAmountFen)" />
            <wd-cell title="支付方式" :value="PAY_WAY_LABELS[detail.order.payWay]" />
          </wd-cell-group>
        </wd-card>
      </view>

      <view v-if="playbackDone" class="page-section">
        <wd-button v-if="isTerminal" block size="large" @click="goDetail">
          查看订单详情
        </wd-button>
        <template v-else>
          <wd-button block plain size="large" @click="switchToTab('U02')">
            稍后在订单列表查看
          </wd-button>
          <view class="muted-text action-note">
            设备回传结果由固定快照决定，本页不提供人工推进按钮。
          </view>
        </template>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.loading-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 64px 0;
}

.status-actions {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}

.progress-status {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 4px 0;
}

.progress-status-text {
  font-size: 24px;
  font-weight: 600;
}

.step-desc {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}

.action-note {
  margin-top: 8px;
  text-align: center;
}
</style>
