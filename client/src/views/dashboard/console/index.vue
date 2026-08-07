<template>
  <div class="overview-page">
    <header class="overview-header">
      <div class="overview-heading">
        <h1>运营总览</h1>
        <span v-if="statsAsOf" class="overview-as-of">统计截至 {{ statsAsOf }}</span>
      </div>
      <ElButton class="refresh-action" text :loading="loading" @click="reload">
        <ArtSvgIcon v-if="!loading" icon="ri:refresh-line" />
        <span>刷新</span>
      </ElButton>
    </header>

    <div v-loading="loading" class="overview-main">
      <div class="overview-primary">
        <section class="overview-panel business-panel" aria-label="今日经营">
          <div class="business-metric business-metric--primary">
            <span class="metric-label">今日取水订单</span>
            <div class="metric-value-row">
              <ArtCountTo class="metric-value metric-value--large" :target="s.todayWaterOrders" />
              <span class="metric-unit">单</span>
            </div>
          </div>

          <div class="business-metric business-metric--revenue">
            <span class="metric-label">今日营收</span>
            <div class="metric-value-row">
              <span class="metric-currency">¥</span>
              <span class="metric-value metric-value--revenue">
                {{ (s.todayRevenue / 100).toFixed(2) }}
              </span>
            </div>
          </div>

          <div class="order-state-grid">
            <div
              v-for="item in orderStates"
              :key="item.label"
              class="order-state"
              :class="`order-state--${item.tone}`"
            >
              <span class="order-state-label">{{ item.label }}</span>
              <ArtCountTo class="order-state-value" :target="item.value" />
            </div>
          </div>
        </section>

        <section class="overview-panel trend-panel">
          <div class="panel-header">
            <h2 class="panel-title">近 7 日订单趋势</h2>
            <button class="panel-link" type="button" @click="router.push('/order/index')">
              <span>查看全部订单</span>
              <ArtSvgIcon icon="ri:arrow-right-s-line" />
            </button>
          </div>

          <ArtLineChart
            v-if="hasTrend"
            :data="trendSeries"
            :x-axis-data="trendXAxis"
            :colors="TREND_COLORS"
            :y-axis-min-interval="1"
            show-legend
            legend-position="top"
            symbol="circle"
            :symbol-size="6"
            :line-width="2"
            height="268px"
          />

          <div v-else class="trend-empty">
            <div class="trend-empty-copy">近 7 日暂无订单</div>
            <div class="trend-empty-grid" aria-hidden="true">
              <i v-for="line in 4" :key="line" />
            </div>
            <div class="trend-empty-axis">
              <span v-for="date in trendXAxis" :key="date">{{ date }}</span>
            </div>
          </div>
        </section>
      </div>

      <aside class="overview-rail">
        <section class="overview-panel todo-panel">
          <div class="panel-header todo-header">
            <h2 class="panel-title">待处理</h2>
            <span v-if="todoTotal" class="panel-summary">{{ todoTotal }} 项</span>
          </div>

          <div class="todo-list">
            <button
              v-for="item in todos"
              :key="item.label"
              class="todo-item"
              :class="{ 'todo-item--idle': !Number(item.count) }"
              type="button"
              :disabled="!Number(item.count)"
              @click="router.push(item.path)"
            >
              <span class="todo-icon" :class="`todo-icon--${item.tone}`">
                <ArtSvgIcon :icon="item.icon" />
              </span>
              <span class="todo-label">{{ item.label }}</span>
              <span class="todo-count">{{ item.count ?? '—' }}</span>
              <ArtSvgIcon icon="ri:arrow-right-s-line" class="todo-arrow" />
            </button>
          </div>
        </section>

        <section class="overview-panel health-panel">
          <div class="panel-header">
            <h2 class="panel-title">设备与链路</h2>
          </div>

          <div class="health-content">
            <div class="health-ring">
              <ArtRingChart
                :data="ringData"
                :colors="RING_COLORS"
                :radius="['68%', '84%']"
                :show-legend="false"
                height="132px"
              />
              <div class="health-ring-center">
                <strong>{{ s.onlineDevices }}/{{ s.totalDevices }}</strong>
                <span>在线设备</span>
              </div>
            </div>

            <div class="health-stats">
              <div class="health-counts">
                <span><i class="status-dot status-dot--success" />在线 {{ s.onlineDevices }}</span>
                <span><i class="status-dot status-dot--muted" />离线 {{ offlineDevices }}</span>
              </div>
              <div class="health-divider" />
              <span class="health-rate-label">24h 指令成功率</span>
              <strong class="health-rate">{{ commandRateText }}</strong>
              <span class="health-detail">{{ commandDetailText }}</span>
            </div>
          </div>

          <button
            v-if="offlineDevices > 0"
            class="health-alert"
            type="button"
            @click="router.push('/device/index')"
          >
            <ArtSvgIcon icon="ri:alarm-warning-line" />
            <span>{{ offlineDevices }} 台设备离线</span>
            <span class="health-alert-action">查看设备</span>
            <ArtSvgIcon icon="ri:arrow-right-s-line" />
          </button>
        </section>
      </aside>
    </div>

    <div class="evidence-grid">
      <section class="overview-panel evidence-panel">
        <div class="panel-header">
          <h2 class="panel-title">指令与回执</h2>
          <button class="panel-link" type="button" @click="router.push('/device/command')">
            <span>查看全部指令</span>
            <ArtSvgIcon icon="ri:arrow-right-s-line" />
          </button>
        </div>

        <ElTable
          v-loading="cmdLoading"
          class="overview-table"
          :data="commands"
          height="278"
          size="small"
        >
          <ElTableColumn prop="cmdNo" label="指令号" min-width="178" show-overflow-tooltip />
          <ElTableColumn prop="deviceNo" label="设备" min-width="112" show-overflow-tooltip />
          <ElTableColumn label="类型" width="96">
            <template #default="{ row }">{{ cmdTypeLabel(row.cmdType) }}</template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="92">
            <template #default="{ row }">
              <span class="command-status" :class="commandStatusClass(row.cmdStatus)">
                <i />{{ cmdStatusLabel(row.cmdStatus) }}
              </span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="回执" width="86">
            <template #default="{ row }">
              <span :class="row.ackTime ? 'ack-state ack-state--done' : 'ack-state'">
                {{ row.ackTime ? '已回执' : '未回执' }}
              </span>
            </template>
          </ElTableColumn>
        </ElTable>
      </section>

      <section class="overview-panel evidence-panel">
        <div class="panel-header">
          <h2 class="panel-title">审计事件</h2>
        </div>

        <div v-loading="eventLoading" class="event-list">
          <article v-for="event in events" :key="event.id" class="event-item">
            <div class="event-heading">
              <span class="event-type">{{ eventTypeLabel(event.eventType) }}</span>
              <strong class="event-key">{{ event.eventKey }}</strong>
              <time class="event-time">{{ formatTime(event.time) }}</time>
            </div>
            <p class="event-detail">
              <span>{{ portalLabel(event.actorPortal) }}</span>
              <i />
              <span>{{ event.detail || '无补充信息' }}</span>
            </p>
          </article>
          <div v-if="!events.length && !eventLoading" class="event-empty">暂无事件</div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
  import {
    fetchDashboardOverview,
    fetchRecentCommands,
    fetchRecentEvents,
    type AuditEventRow,
    type CommandMonitorRow,
    type OrderTrendPoint,
    type OverviewStats
  } from '@/api/dashboard'
  import { fetchAlarmPage } from '@/api/device'
  import { DictTypeEnum } from '@/constants/dict'
  import { fetchDictByTypes, toDictOptions } from '@/utils/dict'
  import dayjs from 'dayjs'

  defineOptions({ name: 'Console' })

  const router = useRouter()

  // ECharts 渐变计算无法解析 CSS 变量，图表色需传入固定十六进制值。
  const TREND_COLORS = ['#2e7cf6', '#16c784', '#0ea5b7']
  const RING_COLORS = ['#16c784', '#d8dee8']

  const EMPTY_STATS: OverviewStats = {
    todayWaterOrders: 0,
    todayWaterDone: 0,
    todayWaterDispensing: 0,
    todayWaterException: 0,
    todayWaterCanceled: 0,
    todayRevenue: 0,
    onlineDevices: 0,
    totalDevices: 0,
    cmd24hSuccess: 0,
    cmd24hFail: 0,
    cmd24hTimeout: 0,
    cmdSuccessRate: null,
    pendingExceptionOrders: 0,
    pendingAppeals: 0,
    pendingCouriers: 0,
    pendingDeliveries: 0
  }

  const stats = ref<OverviewStats | null>(null)
  const alarmPending = ref<number | null>(null)
  const trendData = ref<OrderTrendPoint[]>([])
  const commands = ref<CommandMonitorRow[]>([])
  const events = ref<AuditEventRow[]>([])
  const loading = ref(false)
  const cmdLoading = ref(false)
  const eventLoading = ref(false)
  const statsAsOf = ref('')

  const cmdStatusOptions = ref<{ label: string; value: number }[]>([])
  const cmdTypeOptions = ref<{ label: string; value: number }[]>([])
  const eventTypeOptions = ref<{ label: string; value: number }[]>([])
  const portalOptions = ref<{ label: string; value: number }[]>([])

  const s = computed<OverviewStats>(() => stats.value ?? EMPTY_STATS)
  const offlineDevices = computed(() => Math.max(0, s.value.totalDevices - s.value.onlineDevices))
  const commandRateText = computed(() =>
    s.value.cmdSuccessRate === null ? '—' : `${s.value.cmdSuccessRate}%`
  )
  const commandDetailText = computed(() =>
    s.value.cmdSuccessRate === null
      ? '近 24 小时暂无指令'
      : `成功 ${s.value.cmd24hSuccess} · 失败 ${s.value.cmd24hFail} · 超时 ${s.value.cmd24hTimeout}`
  )

  const ringData = computed(() => [
    { name: '在线', value: s.value.onlineDevices },
    { name: '离线', value: offlineDevices.value }
  ])

  const orderStates = computed(() => [
    { label: '已完成', value: s.value.todayWaterDone, tone: 'success' },
    { label: '出水中', value: s.value.todayWaterDispensing, tone: 'primary' },
    { label: '异常', value: s.value.todayWaterException, tone: 'warning' },
    { label: '已取消', value: s.value.todayWaterCanceled, tone: 'muted' }
  ])

  const todos = computed(() => [
    {
      label: '活动告警',
      count: alarmPending.value,
      tone: 'warning',
      icon: 'ri:alarm-warning-line',
      path: '/device/alarm'
    },
    {
      label: '异常订单待补偿',
      count: s.value.pendingExceptionOrders,
      tone: 'danger',
      icon: 'ri:error-warning-line',
      path: '/order/index?orderStatus=6'
    },
    {
      label: '申诉待处理',
      count: s.value.pendingAppeals,
      tone: 'primary',
      icon: 'ri:question-answer-line',
      path: '/order/appeal?appealStatus=1'
    },
    {
      label: '配送单待接单',
      count: s.value.pendingDeliveries,
      tone: 'cyan',
      icon: 'ri:truck-line',
      path: '/order/delivery?taskStatus=1'
    },
    {
      label: '配送员待审核',
      count: s.value.pendingCouriers,
      tone: 'violet',
      icon: 'ri:user-follow-line',
      path: '/user/courier?courierStatus=1'
    }
  ])

  // 后端 Long 在前端边界可能是字符串，计数聚合前统一归一为 number。
  const todoTotal = computed(() =>
    todos.value.reduce((total, item) => total + Number(item.count || 0), 0)
  )

  const trendXAxis = computed(() =>
    trendData.value.map((point) =>
      point.date.length === 8 ? `${point.date.slice(4, 6)}-${point.date.slice(6, 8)}` : point.date
    )
  )
  const trendSeries = computed(() => [
    { name: '扫码取水', data: trendData.value.map((point) => point.waterOrders) },
    { name: '购卡充值', data: trendData.value.map((point) => point.rechargeOrders) },
    { name: '水配送', data: trendData.value.map((point) => point.deliveryOrders) }
  ])
  const hasTrend = computed(
    () =>
      trendData.value.length > 0 &&
      trendData.value.some(
        (point) => point.waterOrders + point.rechargeOrders + point.deliveryOrders > 0
      )
  )

  async function loadDashboard() {
    loading.value = true
    cmdLoading.value = true
    eventLoading.value = true
    try {
      const [overview, recentCommands, recentEvents, dicts] = await Promise.all([
        fetchDashboardOverview(),
        fetchRecentCommands(),
        fetchRecentEvents(),
        fetchDictByTypes([
          DictTypeEnum.指令状态,
          DictTypeEnum.指令类型,
          DictTypeEnum.领域事件类型,
          DictTypeEnum.操作端口
        ])
      ])
      stats.value = overview.stats
      trendData.value = overview.trend
      commands.value = recentCommands
      events.value = recentEvents
      statsAsOf.value = dayjs().format('YYYY-MM-DD HH:mm')

      const pick = (type: string) =>
        toDictOptions(dicts.find((dict) => dict.dictType === type)?.dictDataList || [])
      cmdStatusOptions.value = pick(DictTypeEnum.指令状态)
      cmdTypeOptions.value = pick(DictTypeEnum.指令类型)
      eventTypeOptions.value = pick(DictTypeEnum.领域事件类型)
      portalOptions.value = pick(DictTypeEnum.操作端口)
    } finally {
      loading.value = false
      cmdLoading.value = false
      eventLoading.value = false
    }
  }

  async function loadAlarmPending() {
    try {
      const page = await fetchAlarmPage({ current: 1, size: 1, alarmStatus: 1 })
      alarmPending.value = Number(page.total ?? 0)
    } catch {
      alarmPending.value = null
    }
  }

  function reload() {
    void loadDashboard()
    void loadAlarmPending()
  }

  onMounted(reload)

  onActivated(async () => {
    if (!events.value.length) return
    eventLoading.value = true
    try {
      events.value = await fetchRecentEvents()
    } finally {
      eventLoading.value = false
    }
  })

  const cmdStatusLabel = (value: number) =>
    cmdStatusOptions.value.find((option) => option.value === value)?.label || String(value)
  const cmdTypeLabel = (value?: number) =>
    value == null
      ? '-'
      : cmdTypeOptions.value.find((option) => option.value === value)?.label || String(value)
  const eventTypeLabel = (value?: number) =>
    value == null
      ? '事件'
      : eventTypeOptions.value.find((option) => option.value === value)?.label || `类型${value}`
  const portalLabel = (value?: number) =>
    value == null
      ? '系统'
      : portalOptions.value.find((option) => option.value === value)?.label || `端口${value}`

  const commandStatusClass = (value: number) => {
    if (value === 4) return 'command-status--success'
    if (value === 5 || value === 6) return 'command-status--danger'
    if (value === 7) return 'command-status--warning'
    return 'command-status--primary'
  }

  const formatTime = (time?: string) => {
    if (!time || time.length !== 14) return time || '-'
    return `${time.slice(4, 6)}-${time.slice(6, 8)} ${time.slice(8, 10)}:${time.slice(10, 12)}`
  }
</script>

<style scoped lang="scss">
  .overview-page {
    --overview-surface: var(--default-box-color);
    --overview-border: var(--art-card-border);
    --overview-text: var(--art-gray-900);
    --overview-muted: var(--art-gray-500);
    --overview-radius: 14px;

    padding-bottom: 20px;
    color: var(--overview-text);
  }

  .overview-header,
  .overview-heading,
  .panel-header,
  .metric-value-row,
  .event-heading,
  .event-detail {
    display: flex;
    align-items: center;
  }

  .overview-header {
    justify-content: space-between;
    min-height: 52px;
    margin-bottom: 16px;
  }

  .overview-heading {
    gap: 18px;
  }

  .overview-heading h1 {
    margin: 0;
    font-size: 24px;
    font-weight: 650;
    line-height: 1.25;
    letter-spacing: -0.02em;
  }

  .overview-as-of {
    font-size: 13px;
    color: var(--overview-muted);
  }

  .refresh-action {
    display: inline-flex;
    gap: 6px;
    align-items: center;
    min-height: 36px;
    padding: 0 10px !important;
    font-size: 13px;
    font-weight: 500;
  }

  .overview-main {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(320px, 360px);
    gap: 16px;
    align-items: stretch;
  }

  .overview-primary,
  .overview-rail {
    display: flex;
    flex-direction: column;
    gap: 16px;
    min-width: 0;
  }

  .overview-panel {
    box-sizing: border-box;
    min-width: 0;
    background: var(--overview-surface);
    border: 1px solid var(--overview-border);
    border-radius: var(--overview-radius);
    box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
  }

  .panel-header {
    justify-content: space-between;
    min-height: 28px;
    margin-bottom: 14px;
  }

  .panel-title {
    margin: 0;
    font-size: 16px;
    font-weight: 650;
    line-height: 1.4;
    letter-spacing: -0.01em;
  }

  .panel-link {
    display: inline-flex;
    gap: 2px;
    align-items: center;
    padding: 4px 0;
    font: inherit;
    font-size: 13px;
    font-weight: 500;
    color: var(--el-color-primary);
    cursor: pointer;
    background: transparent;
    border: 0;
  }

  .panel-link:hover {
    color: var(--el-color-primary-light-3);
  }

  .panel-summary {
    font-size: 13px;
    font-variant-numeric: tabular-nums;
    color: var(--overview-muted);
  }

  .business-panel {
    display: grid;
    grid-template-columns: minmax(146px, 0.9fr) minmax(178px, 1.1fr) minmax(330px, 2fr);
    min-height: 164px;
    overflow: hidden;
  }

  .business-metric {
    display: flex;
    flex-direction: column;
    justify-content: center;
    min-width: 0;
    padding: 22px 24px;
  }

  .business-metric + .business-metric,
  .order-state-grid {
    border-left: 1px solid var(--overview-border);
  }

  .metric-label,
  .order-state-label,
  .health-rate-label {
    font-size: 13px;
    color: var(--art-gray-600);
  }

  .metric-value-row {
    gap: 7px;
    margin-top: 12px;
  }

  .metric-value {
    font-weight: 680;
    font-variant-numeric: tabular-nums;
    line-height: 1;
    color: var(--overview-text);
    letter-spacing: -0.035em;
  }

  .metric-value--large {
    font-size: clamp(34px, 2.6vw, 44px);
  }

  .metric-value--revenue {
    overflow: hidden;
    font-size: clamp(27px, 2.1vw, 36px);
    text-overflow: ellipsis;
  }

  .metric-unit,
  .metric-currency {
    flex: none;
    font-size: 15px;
    font-weight: 500;
    color: var(--overview-muted);
  }

  .metric-currency {
    align-self: baseline;
    font-size: 20px;
    color: var(--overview-text);
  }

  .order-state-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    align-items: center;
    min-width: 0;
    padding: 18px 8px;
  }

  .order-state {
    display: flex;
    flex-direction: column;
    gap: 12px;
    align-items: center;
    min-width: 0;
    padding: 4px 10px;
  }

  .order-state + .order-state {
    border-left: 1px solid var(--overview-border);
  }

  .order-state-value {
    font-size: clamp(22px, 1.75vw, 30px);
    font-weight: 680;
    font-variant-numeric: tabular-nums;
    line-height: 1;
    letter-spacing: -0.025em;
  }

  .order-state--success .order-state-value {
    color: #16b978;
  }

  .order-state--primary .order-state-value {
    color: var(--el-color-primary);
  }

  .order-state--warning .order-state-value {
    color: #f59e0b;
  }

  .order-state--muted .order-state-value {
    color: var(--overview-muted);
  }

  .trend-panel {
    flex: 1;
    min-height: 344px;
    padding: 18px 20px 14px;
  }

  .trend-empty {
    position: relative;
    display: flex;
    flex-direction: column;
    justify-content: flex-end;
    height: 268px;
    padding: 36px 12px 13px;
  }

  .trend-empty-copy {
    position: absolute;
    inset: 42% 0 auto;
    z-index: 1;
    font-size: 13px;
    color: var(--overview-muted);
    text-align: center;
  }

  .trend-empty-grid {
    position: absolute;
    inset: 32px 12px 42px;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
  }

  .trend-empty-grid i {
    border-top: 1px dashed var(--overview-border);
  }

  .trend-empty-axis {
    display: flex;
    justify-content: space-between;
    padding: 0 2px;
    font-size: 12px;
    color: var(--overview-muted);
  }

  .todo-panel,
  .health-panel,
  .evidence-panel {
    padding: 16px 18px;
  }

  .todo-panel {
    flex: 1;
  }

  .todo-header {
    margin-bottom: 5px;
  }

  .todo-list {
    display: flex;
    flex-direction: column;
  }

  .todo-item {
    display: grid;
    grid-template-columns: 32px minmax(0, 1fr) auto 16px;
    gap: 10px;
    align-items: center;
    min-height: 44px;
    padding: 5px 2px;
    font: inherit;
    color: var(--overview-text);
    text-align: left;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-top: 1px solid var(--overview-border);
    transition:
      color 160ms ease,
      background 160ms ease;
  }

  .todo-item:not(:disabled):hover {
    color: var(--el-color-primary);
    background: var(--art-gray-100);
  }

  .todo-item--idle {
    cursor: default;
  }

  .todo-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    font-size: 16px;
    color: var(--overview-muted);
    background: var(--art-gray-100);
    border-radius: 9px;
  }

  .todo-icon--danger {
    color: #ef5b5b;
    background: rgb(239 91 91 / 10%);
  }

  .todo-icon--warning {
    color: #f59e0b;
    background: rgb(245 158 11 / 10%);
  }

  .todo-icon--primary {
    color: var(--el-color-primary);
    background: rgb(46 124 246 / 10%);
  }

  .todo-icon--cyan {
    color: #0ea5b7;
    background: rgb(14 165 183 / 10%);
  }

  .todo-icon--violet {
    color: #7c5cff;
    background: rgb(124 92 255 / 10%);
  }

  .todo-item--idle .todo-icon {
    color: var(--overview-muted);
    background: var(--art-gray-100);
  }

  .todo-label {
    overflow: hidden;
    font-size: 14px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .todo-count {
    min-width: 24px;
    font-size: 15px;
    font-weight: 650;
    font-variant-numeric: tabular-nums;
    text-align: right;
  }

  .todo-item--idle .todo-count,
  .todo-item--idle .todo-label,
  .todo-arrow {
    color: var(--overview-muted);
  }

  .health-panel {
    min-height: 228px;
  }

  .health-content {
    display: grid;
    grid-template-columns: 132px minmax(0, 1fr);
    gap: 14px;
    align-items: center;
  }

  .health-ring {
    position: relative;
  }

  .health-ring-center {
    position: absolute;
    top: 50%;
    left: 50%;
    display: flex;
    flex-direction: column;
    align-items: center;
    pointer-events: none;
    transform: translate(-50%, -50%);
  }

  .health-ring-center strong {
    font-size: 21px;
    font-weight: 680;
    font-variant-numeric: tabular-nums;
    line-height: 1.15;
    letter-spacing: -0.02em;
  }

  .health-ring-center span {
    margin-top: 4px;
    font-size: 11px;
    color: var(--overview-muted);
    white-space: nowrap;
  }

  .health-stats {
    min-width: 0;
  }

  .health-counts {
    display: flex;
    flex-wrap: wrap;
    gap: 8px 14px;
    font-size: 12px;
    color: var(--art-gray-600);
  }

  .health-counts span {
    display: inline-flex;
    align-items: center;
    white-space: nowrap;
  }

  .status-dot {
    display: inline-block;
    width: 7px;
    height: 7px;
    margin-right: 6px;
    border-radius: 50%;
  }

  .status-dot--success {
    background: #16c784;
  }

  .status-dot--muted {
    background: #aab4c3;
  }

  .health-divider {
    margin: 13px 0;
    border-top: 1px solid var(--overview-border);
  }

  .health-rate-label,
  .health-detail {
    display: block;
  }

  .health-rate {
    display: block;
    margin-top: 5px;
    font-size: 28px;
    font-weight: 680;
    font-variant-numeric: tabular-nums;
    line-height: 1.1;
    letter-spacing: -0.03em;
  }

  .health-detail {
    margin-top: 7px;
    overflow: hidden;
    font-size: 11px;
    line-height: 1.5;
    color: var(--overview-muted);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .health-alert {
    display: grid;
    grid-template-columns: 18px minmax(0, 1fr) auto 16px;
    gap: 7px;
    align-items: center;
    width: 100%;
    min-height: 38px;
    padding: 6px 10px;
    margin-top: 12px;
    font: inherit;
    font-size: 12px;
    color: #c77905;
    text-align: left;
    cursor: pointer;
    background: rgb(245 158 11 / 9%);
    border: 0;
    border-radius: 9px;
  }

  .health-alert-action {
    color: var(--el-color-primary);
  }

  .evidence-grid {
    display: grid;
    grid-template-columns: minmax(0, 1.08fr) minmax(380px, 0.92fr);
    gap: 16px;
    margin-top: 16px;
  }

  .evidence-panel {
    min-height: 342px;
    overflow: hidden;
  }

  .overview-table {
    --el-table-border-color: var(--overview-border);
    --el-table-header-bg-color: var(--art-gray-100);
    --el-table-row-hover-bg-color: var(--art-gray-100);
    --el-table-bg-color: transparent;
    --el-table-tr-bg-color: transparent;

    width: 100%;
    font-size: 12px;
  }

  .overview-table :deep(.el-table__inner-wrapper::before) {
    display: none;
  }

  .overview-table :deep(th.el-table__cell) {
    height: 36px;
    padding: 0;
    font-weight: 600;
    color: var(--art-gray-600);
  }

  .overview-table :deep(td.el-table__cell) {
    height: 40px;
    padding: 0;
    color: var(--overview-text);
  }

  .command-status {
    display: inline-flex;
    align-items: center;
    font-size: 12px;
    white-space: nowrap;
  }

  .command-status i {
    width: 6px;
    height: 6px;
    margin-right: 6px;
    background: currentcolor;
    border-radius: 50%;
  }

  .command-status--success,
  .ack-state--done {
    color: #16b978;
  }

  .command-status--danger {
    color: #ef5b5b;
  }

  .command-status--warning {
    color: #f59e0b;
  }

  .command-status--primary {
    color: var(--el-color-primary);
  }

  .ack-state {
    font-size: 12px;
    color: var(--overview-muted);
  }

  .event-list {
    height: 278px;
    overflow-y: auto;
    scrollbar-width: thin;
  }

  .event-item {
    padding: 11px 2px;
  }

  .event-item + .event-item {
    border-top: 1px solid var(--overview-border);
  }

  .event-heading {
    gap: 9px;
    min-width: 0;
  }

  .event-type {
    flex: none;
    min-width: 68px;
    font-size: 12px;
    color: var(--art-gray-600);
  }

  .event-key {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    font-size: 12px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .event-time {
    flex: none;
    font-size: 11px;
    font-variant-numeric: tabular-nums;
    color: var(--overview-muted);
  }

  .event-detail {
    gap: 7px;
    min-width: 0;
    margin: 5px 0 0 77px;
    font-size: 11px;
    line-height: 1.45;
    color: var(--overview-muted);
  }

  .event-detail i {
    flex: none;
    width: 3px;
    height: 3px;
    background: var(--overview-muted);
    border-radius: 50%;
  }

  .event-detail span:first-child {
    flex: none;
    white-space: nowrap;
  }

  .event-detail span:last-child {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .event-empty {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
    font-size: 13px;
    color: var(--overview-muted);
  }

  @media (width <= 1340px) {
    .overview-main {
      grid-template-columns: minmax(0, 1fr) 320px;
    }

    .business-panel {
      grid-template-columns: minmax(126px, 0.8fr) minmax(146px, 1fr) minmax(280px, 2fr);
    }

    .business-metric {
      padding-inline: 18px;
    }

    .order-state {
      padding-inline: 6px;
    }

    .health-content {
      grid-template-columns: 126px minmax(0, 1fr);
    }
  }

  @media (width <= 1080px) {
    .overview-main,
    .evidence-grid {
      grid-template-columns: 1fr;
    }

    .overview-primary,
    .overview-rail {
      display: contents;
    }

    .business-panel {
      order: 1;
    }

    .todo-panel {
      flex: none;
      order: 2;
    }

    .trend-panel {
      order: 3;
    }

    .health-panel {
      order: 4;
    }

    .todo-list {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0 20px;
    }

    .health-content {
      grid-template-columns: 132px minmax(0, 1fr);
    }
  }

  @media (width <= 720px) {
    .overview-header {
      align-items: flex-start;
    }

    .overview-heading {
      flex-direction: column;
      gap: 4px;
      align-items: flex-start;
    }

    .overview-heading h1 {
      font-size: 22px;
    }

    .business-panel {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .business-metric--revenue {
      border-left: 1px solid var(--overview-border);
    }

    .order-state-grid {
      grid-column: 1 / -1;
      border-top: 1px solid var(--overview-border);
      border-left: 0;
    }

    .todo-list {
      grid-template-columns: 1fr;
    }

    .health-content {
      grid-template-columns: 132px minmax(0, 1fr);
    }

    .evidence-grid {
      grid-template-columns: minmax(0, 1fr);
    }

    .evidence-panel {
      padding-inline: 14px;
    }
  }

  @media (width <= 500px) {
    .business-panel {
      grid-template-columns: 1fr;
    }

    .business-metric + .business-metric {
      border-top: 1px solid var(--overview-border);
      border-left: 0;
    }

    .order-state-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .order-state:nth-child(3) {
      border-left: 0;
    }

    .order-state:nth-child(n + 3) {
      padding-top: 16px;
      border-top: 1px solid var(--overview-border);
    }

    .health-content {
      grid-template-columns: 1fr;
    }

    .health-ring {
      max-width: 160px;
      margin: 0 auto;
    }

    .event-heading {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
    }

    .event-time {
      grid-column: 2;
    }

    .event-detail {
      margin-left: 0;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .todo-item,
    .panel-link {
      transition: none;
    }
  }
</style>
