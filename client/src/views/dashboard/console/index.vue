<!-- 运营总览（5+1 之一）：订单 / 设备 / 命令 / ACK / 审计。 -->
<template>
  <div class="overview-page">
    <div class="overview-toolbar mb-4">
      <div>
        <h2 class="overview-title">运营总览</h2>
        <div class="overview-subtitle">订单、设备、指令回执与运营待办</div>
      </div>
      <div class="overview-meta">
        <ElTag type="info" effect="plain">演示数据</ElTag>
        <span>统计截至 2026-07-14 13:00</span>
      </div>
    </div>

    <!-- 区块一：核心指标 -->
    <ElRow :gutter="16" class="stat-row">
      <ElCol v-for="card in statCards" :key="card.label" :xs="24" :sm="12" :md="6" class="stat-col">
        <ElCard shadow="never" class="stat-card">
          <div class="stat-label">{{ card.label }}</div>
          <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
          <div class="stat-sub">{{ card.sub }}</div>
        </ElCard>
      </ElCol>
    </ElRow>

    <!-- 区块二：待处理事项（点击直达对应模块，动线闭环） -->
    <ElCard shadow="never" class="mt-4">
      <template #header>
        <span class="card-title">待处理事项</span>
      </template>
      <ElRow :gutter="16">
        <ElCol v-for="todo in todoCards" :key="todo.label" :xs="12" :sm="6">
          <div class="todo-item" @click="router.push(todo.path)">
            <ElBadge
              :value="todo.count"
              :hidden="todo.count === 0"
              :type="todo.count ? 'danger' : 'info'"
            >
              <div class="todo-label">{{ todo.label }}</div>
            </ElBadge>
            <div class="todo-hint">{{ todo.count > 0 ? '点击处理' : '无待办' }}</div>
          </div>
        </ElCol>
      </ElRow>
    </ElCard>

    <!-- 区块三：近 7 日订单趋势（三类订单堆叠） -->
    <ElCard shadow="never" class="mt-4">
      <template #header>
        <span class="card-title">近 7 日订单趋势</span>
      </template>
      <ArtBarChart
        v-if="trendData.length"
        :data="trendSeries"
        :x-axis-data="trendXAxis"
        stack
        height="260px"
      />
    </ElCard>

    <ElRow :gutter="16" class="mt-4">
      <!-- 区块四：指令与回执监控（命令/ACK，MVP 验收口径） -->
      <ElCol :md="14" :sm="24">
        <ElCard shadow="never">
          <template #header>
            <div class="flex items-center justify-between">
              <span class="card-title">指令与回执监控</span>
              <ElButton size="small" link type="primary" @click="router.push('/device/command')"
                >全部指令 →</ElButton
              >
            </div>
          </template>
          <ElTable :data="commands" size="small" v-loading="cmdLoading">
            <ElTableColumn prop="cmdNo" label="指令号" min-width="180" show-overflow-tooltip />
            <ElTableColumn prop="deviceNo" label="设备" width="110" />
            <ElTableColumn prop="cmdTypeLabel" label="类型" width="90" />
            <ElTableColumn label="状态" width="95">
              <template #default="{ row }">
                <ElTag size="small" :type="cmdTagType(row.cmdStatus)">
                  {{ cmdStatusLabel(row.cmdStatus) }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="ACK" width="80">
              <template #default="{ row }">
                <ElIcon v-if="row.ackTime" class="text-success"><CircleCheckFilled /></ElIcon>
                <span v-else class="text-secondary">-</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="结果" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.failReason || '正常' }}</template>
            </ElTableColumn>
          </ElTable>
        </ElCard>
      </ElCol>

      <!-- 区块五：共享 Demo 审计事件（状态变化级留痕，REQ-024/050） -->
      <ElCol :md="10" :sm="24">
        <ElCard shadow="never">
          <template #header>
            <div class="flex items-center justify-between">
              <span class="card-title">审计事件流（状态变化必留痕）</span>
              <ElTag type="info" size="small" effect="plain">共享 Mock</ElTag>
            </div>
          </template>
          <ElTimeline class="pl-1" v-loading="eventLoading">
            <ElTimelineItem
              v-for="event in events"
              :key="event.id"
              :timestamp="formatTime(event.time)"
              placement="top"
              size="normal"
            >
              <div class="text-sm">
                <ElTag size="small" :type="event.tone" effect="plain" class="mr-1">{{
                  event.eventTypeLabel
                }}</ElTag>
                <span class="font-medium">{{ event.eventKey }}</span>
              </div>
              <div class="mt-1 text-xs text-secondary">
                {{ event.actorLabel }} · {{ event.detail }}
              </div>
            </ElTimelineItem>
          </ElTimeline>
        </ElCard>
      </ElCol>
    </ElRow>
  </div>
</template>

<script setup lang="ts">
  import { CircleCheckFilled } from '@element-plus/icons-vue'
  import {
    fetchOverviewStats,
    fetchOrderTrend,
    fetchRecentCommands,
    fetchRecentEvents,
    type OverviewStats,
    type OrderTrendPoint,
    type CommandMonitorRow,
    type AuditEventRow
  } from '@/api/dashboard'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'

  defineOptions({ name: 'Console' })

  const router = useRouter()

  const stats = ref<OverviewStats | null>(null)
  const trendData = ref<OrderTrendPoint[]>([])
  const commands = ref<CommandMonitorRow[]>([])
  const events = ref<AuditEventRow[]>([])
  const cmdLoading = ref(false)
  const eventLoading = ref(false)

  const cmdStatusOptions = ref<{ label: string; value: number }[]>([])

  async function loadDashboard() {
    cmdLoading.value = true
    eventLoading.value = true
    try {
      const [s, t, c, e, dict] = await Promise.all([
        fetchOverviewStats(),
        fetchOrderTrend(),
        fetchRecentCommands(),
        fetchRecentEvents(),
        fetchDictOptions(DictTypeEnum.指令状态)
      ])
      stats.value = s
      trendData.value = t
      commands.value = c
      events.value = e
      cmdStatusOptions.value = toDictOptions(dict)
    } finally {
      cmdLoading.value = false
      eventLoading.value = false
    }
  }

  onMounted(loadDashboard)

  // 页面被 KeepAlive 后再次激活时，只刷新共享 Mock 审计，确保刚完成的操作可被追溯。
  onActivated(async () => {
    if (!events.value.length) return
    eventLoading.value = true
    try {
      events.value = await fetchRecentEvents()
    } finally {
      eventLoading.value = false
    }
  })

  const cmdStatusLabel = (v: number) =>
    cmdStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const cmdTagType = (v: number) =>
    v === 4 ? 'success' : v === 5 || v === 6 ? 'danger' : v === 7 ? 'warning' : 'primary'

  const formatTime = (t?: string) => {
    if (!t || t.length !== 14) return t || '-'
    return `${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}`
  }

  /** 指标卡（同一套固定 Mock 故事线，不等同于真实接口实时聚合） */
  const statCards = computed(() => {
    const s = stats.value
    if (!s) return []
    return [
      {
        label: '今日取水订单',
        value: String(s.todayWaterOrders),
        sub: '完成 1 · 出水中 1 · 异常 2 · 取消 1',
        color: 'var(--el-color-primary)'
      },
      {
        label: '今日营收',
        value: `￥${(s.todayRevenue / 100).toFixed(2)}`,
        sub: '取水 + 配送（充值不计入当日口径）',
        color: 'var(--el-color-success)'
      },
      {
        label: '设备在线',
        value: `${s.onlineDevices} / ${s.totalDevices}`,
        sub: '离线设备已纳入下单阻断规则',
        color:
          s.onlineDevices < s.totalDevices ? 'var(--el-color-warning)' : 'var(--el-color-success)'
      },
      {
        label: '指令成功率(24h)',
        value: `${s.cmdSuccessRate}%`,
        sub: '成功 6 / 失败 2 / 超时 5',
        color: s.cmdSuccessRate >= 90 ? 'var(--el-color-success)' : 'var(--el-color-warning)'
      }
    ]
  })

  /** 待处理事项（点击直达并携带筛选参数落地，动线闭环，T2 断点 A 整改） */
  const todoCards = computed(() => {
    const s = stats.value
    if (!s) return []
    return [
      {
        label: '异常订单待补偿',
        count: s.pendingExceptionOrders,
        path: '/order/index?orderStatus=6'
      },
      { label: '申诉待处理', count: s.pendingAppeals, path: '/order/appeal?appealStatus=1' },
      { label: '配送单待接单', count: s.pendingDeliveries, path: '/order/delivery?taskStatus=1' },
      { label: '配送员待审核', count: s.pendingCouriers, path: '/user/courier?courierStatus=1' }
    ]
  })

  const trendXAxis = computed(() => trendData.value.map((p) => p.date))
  const trendSeries = computed(() => [
    { name: '扫码取水', data: trendData.value.map((p) => p.waterOrders) },
    { name: '购卡充值', data: trendData.value.map((p) => p.rechargeOrders) },
    { name: '水配送', data: trendData.value.map((p) => p.deliveryOrders) }
  ])
</script>

<style scoped>
  .overview-page {
    padding-bottom: 16px;
  }

  .overview-toolbar {
    display: flex;
    gap: 16px;
    align-items: center;
    justify-content: space-between;
  }

  .overview-title {
    margin: 0;
    font-size: 20px;
    line-height: 1.4;
  }

  .overview-subtitle {
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .overview-meta {
    display: flex;
    gap: 8px;
    align-items: center;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .stat-row {
    row-gap: 16px;
  }

  .stat-col {
    display: flex;
    min-width: 0;
  }

  .stat-card {
    width: 100%;
    height: 100%;
    border-radius: 10px;
  }

  .stat-card :deep(.el-card__body) {
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
    width: 100%;
    height: 100%;
    min-height: 132px;
  }

  .stat-label {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .stat-value {
    margin: 6px 0 4px;
    font-size: 28px;
    font-weight: 700;
    line-height: 1.2;
  }

  .stat-sub {
    margin-top: auto;
    font-size: 12px;
    line-height: 1.5;
    color: var(--el-text-color-secondary);
  }

  .card-title {
    font-size: 14px;
    font-weight: 600;
  }

  .todo-item {
    padding: 14px 16px;
    text-align: center;
    cursor: pointer;
    border: 1px dashed var(--el-border-color);
    border-radius: 8px;
    transition: all 0.2s;
  }

  .todo-item:hover {
    background: var(--el-color-primary-light-9);
    border-color: var(--el-color-primary);
  }

  .todo-label {
    padding: 0 6px;
    font-size: 14px;
    font-weight: 500;
  }

  .todo-hint {
    margin-top: 6px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .text-secondary {
    color: var(--el-text-color-secondary);
  }

  .text-success {
    color: var(--el-color-success);
  }

  @media (width <= 768px) {
    .overview-toolbar {
      flex-direction: column;
      align-items: flex-start;
    }
  }
</style>
