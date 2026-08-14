<!--
  订单中心：取水/充值/配送三类订单 + 全链路追溯（REQ-022/050）+ 售后台账（页内视图切换接入，
  不新增一级菜单/路由）。旧深链参数保持原语义，view 缺省即订单查询。

  本轮（S3）三处调整：
  1. 订单查询回到仓库统一的 ArtSearchBar + ArtTableHeader + ArtTable + useTable 体系；
  2. 「异常待补偿」从常规列表里分出独立队列入口，带返回路径（返回时原筛选照旧带回）；
  3. 顶部待办计数只读，每格点进去就是这批单子所在的列表——计数取各自列表查询的总数，
     只把每页条数压到 1，绝不在前端另拼一套聚合（拼出来的数字迟早和列表对不上）；
     售后各格的切分只取 AFTER_SALE_TODO_STATUSES（唯一一份归组定义），页面不另写一套状态切分。
-->
<template>
  <div class="order-page art-full-height">
    <BusinessModuleNav module-key="order" compact />

    <!-- 待办计数：只读，不提供任何写动作；一格＝一个查询条件，点进去就是产出这个数字的那张列表 -->
    <section v-if="metrics.length" class="order-overview" aria-label="待办事项">
      <header class="order-overview__header">
        <div class="order-overview__title">待办事项</div>
      </header>

      <div class="order-overview__grid">
        <button
          v-for="metric in metrics"
          :key="metric.key"
          type="button"
          class="order-overview__metric"
          :class="{ 'is-active': isMetricActive(metric) }"
          :aria-pressed="isMetricActive(metric)"
          @click="openMetric(metric)"
        >
          <span class="order-overview__icon" :class="metric.iconStyle" aria-hidden="true">
            <ArtSvgIcon :icon="metric.icon" />
          </span>
          <span class="order-overview__label">{{ metric.label }}</span>
          <span class="order-overview__value">
            <strong>{{ metric.count ?? '—' }}</strong>
            <small>{{ metricDescription(metric) }}</small>
          </span>
          <ArtSvgIcon icon="ri:arrow-right-s-line" class="order-overview__arrow" />
        </button>
      </div>
    </section>

    <ElCard class="order-view-switch" shadow="never">
      <div class="order-view-switch__inner">
        <div class="order-view-switch__heading">
          <span class="order-view-switch__eyebrow">工作队列</span>
          <span class="order-view-switch__current">{{ activeViewLabel }}</span>
        </div>
        <ElRadioGroup :model-value="activeView" @change="handleViewChange">
          <ElRadioButton value="order">订单查询</ElRadioButton>
          <ElRadioButton value="exception">异常待补偿</ElRadioButton>
          <ElRadioButton value="aftersale">售后台账</ElRadioButton>
        </ElRadioGroup>
        <ElButton v-if="activeView !== 'order'" link type="primary" @click="backToOrderList">
          返回订单查询
        </ElButton>
      </div>
    </ElCard>

    <ElCard v-show="activeView === 'aftersale'" class="art-table-card">
      <AfterSaleLedger
        ref="ledgerRef"
        :active="activeView === 'aftersale'"
        :initial-keyword="afterSaleKeyword"
        :initial-action-status="afterSaleActionStatus"
        @trace="openTraceById"
        @keyword-change="handleAfterSaleKeywordChange"
        @action-status-change="handleAfterSaleActionStatusChange"
      />
    </ElCard>

    <OrderExceptionQueue
      v-if="activeView === 'exception'"
      ref="exceptionRef"
      :active="activeView === 'exception'"
      @trace="openTraceById"
      @reconcile="openWaterReconcile"
      @refund="openRechargeRefund"
    />

    <template v-if="activeView === 'order'">
      <ArtSearchBar
        v-model="searchForm"
        :items="searchItems"
        auto-search
        @search="applyFilters"
        @reset="handleReset"
      />

      <ElCard class="art-table-card">
        <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="handleRefresh">
          <template #left>
            <BusinessTableSummary :total="pagination.total" :page-size="data.length" unit="单">
              <span class="table-summary-amount">本页金额 ￥{{ pageAmountYuan }}</span>
            </BusinessTableSummary>
          </template>
          <template #right>
            <ArtExcelExport
              :data="orderExportRows"
              :filename="orderExportFilename"
              sheet-name="订单查询"
              type="primary"
              size="small"
              plain
              auto-index
            >
              <span class="export-button-content">
                <ArtSvgIcon icon="ri:file-excel-2-line" />
                导出当前页
              </span>
            </ArtExcelExport>
          </template>
        </ArtTableHeader>

        <ArtTable
          :loading="loading"
          :data="data"
          :columns="columns"
          :pagination="pagination"
          @pagination:size-change="handleSizeChange"
          @pagination:current-change="handleCurrentChange"
        >
          <template #userName="{ row }">
            <ElButton
              v-if="canViewUserProfile && row.userId"
              type="primary"
              link
              class="user-link"
              @click="goUserProfile(row.userId)"
            >
              {{ row.userName || row.userId }}
            </ElButton>
            <template v-else>{{ row.userName || '-' }}</template>
            <template v-if="row.actorMaskedPhone">（{{ row.actorMaskedPhone }}）</template>
            <ElTag
              v-if="row.accessRole === 'MEMBER'"
              type="warning"
              size="small"
              effect="plain"
              class="ml-1"
            >
              成员用卡
            </ElTag>
          </template>

          <template #stationName="{ row }">
            <template v-if="row.stationName">
              {{ row.stationName }}
              <div v-if="row.deviceNo" class="text-xs text-g-500"
                >{{ row.deviceNo }} · {{ row.outletNo }}号口</div
              >
            </template>
            <template v-else>-</template>
          </template>

          <template #planMl="{ row }">
            <template v-if="row.planMl">
              {{ mlToLiter(row.planMl) }} →
              <span
                :class="
                  row.actualMl != null && row.actualMl < row.planMl
                    ? 'font-semibold text-warning'
                    : ''
                "
              >
                {{ row.actualMl != null ? mlToLiter(row.actualMl) : '…' }}
              </span>
            </template>
            <template v-else>-</template>
          </template>

          <template #operation="{ row }">
            <ElButton type="primary" size="small" link @click="openTraceById(row.id)">
              全链路追溯
            </ElButton>
            <!-- 取水核账：先读只读依据，再由服务端在事务内重算终态 -->
            <template v-if="row.orderType === 1 && row.orderStatus === 6">
              <ElTooltip v-if="!canQueryAfterSale" content="缺少售后台账查询权限" placement="top">
                <span>
                  <ElButton type="warning" size="small" link disabled>取水核账</ElButton>
                </span>
              </ElTooltip>
              <ElButton v-else type="warning" size="small" link @click="openWaterReconcile(row)">
                取水核账
              </ElButton>
            </template>
            <!-- 充值退款：只对已完成充值订单给入口（未入账走全额退款路径） -->
            <template v-if="rechargeRefundEntryMode(row)">
              <ElTooltip
                v-if="!canRefundRecharge"
                content="缺少充值退款财务审核权限"
                placement="top"
              >
                <span>
                  <ElButton type="danger" size="small" link disabled>
                    {{
                      rechargeRefundEntryMode(row) === 'unsettled' ? '异常单原路退款' : '充值退款'
                    }}
                  </ElButton>
                </span>
              </ElTooltip>
              <ElButton v-else type="danger" size="small" link @click="openRechargeRefund(row)">
                {{ rechargeRefundEntryMode(row) === 'unsettled' ? '异常单原路退款' : '充值退款' }}
              </ElButton>
            </template>
          </template>
        </ArtTable>
      </ElCard>
    </template>

    <!-- 追溯抽屉与核账弹窗被各视图共用，故放在视图分支之外 -->
    <OrderTraceDrawer v-model:visible="traceVisible" :order-id="currentOrderId" />
    <WaterReconcileDialog
      v-model:visible="reconcileVisible"
      :order-id="reconcileOrderId"
      @done="handleReconcileDone"
    />
    <RechargeRefundDialog
      v-model:visible="rechargeRefundVisible"
      :order-id="rechargeRefundOrderId"
      :mode="rechargeRefundMode"
      :order-no="rechargeRefundOrderNo"
      :order-amount="rechargeRefundOrderAmount"
      @done="handleRechargeRefundDone"
    />
  </div>
</template>

<script setup lang="ts">
  import { useTable } from '@/hooks/core/useTable'
  import { fetchOrderPage, paymentEvidenceStateOf, type OrderItem } from '@/api/order'
  import { fetchAfterSaleActionPage } from '@/api/after-sale'
  import {
    AfterSaleActionStatus,
    AfterSalePerms,
    rechargeRefundEntryMode,
    type RechargeRefundEntryMode
  } from '@/api/after-sale-entry'
  import { AFTER_SALE_TODO_STATUSES } from '@/api/finance'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { fenToYuan, mlToLiter } from '@/utils/format'
  import { DictTypeEnum } from '@/constants/dict'
  import OrderTraceDrawer from './modules/order-trace-drawer.vue'
  import AfterSaleLedger from './modules/after-sale-ledger.vue'
  import OrderExceptionQueue from './modules/order-exception-queue.vue'
  import WaterReconcileDialog from './modules/water-reconcile-dialog.vue'
  import RechargeRefundDialog from './modules/recharge-refund-dialog.vue'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import BusinessTableSummary from '@/components/business/business-table-summary/index.vue'
  import { currentPageExportFilename, currentPageExportRows } from '@/utils/current-page-export'
  import { useUserStore } from '@/store/modules/user'
  import { ElTag } from 'element-plus'

  defineOptions({ name: 'OrderList' })

  /** 订单状态(1341)：6=异常待补偿，异常队列的唯一入口条件。 */
  const ORDER_STATUS_ABNORMAL = 6

  const route = useRoute()
  const router = useRouter()
  const userStore = useUserStore()

  const canViewUserProfile = computed(() =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === 'user:user:query')
  )

  /** 售后查询权限：无此权限时不给核账入口，后端同样 403。 */
  const canQueryAfterSale = computed(() =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === AfterSalePerms.query)
  )

  /**
   * 充值退款走独立财务审核权限（R0-7）：与售后台账的查询/处理权分开。
   * 没有它就不给入口——后端同样 403，提前拦住是为了让运营看到原因而不是一个红条。
   */
  const canRefundRecharge = computed(() =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === AfterSalePerms.refund)
  )

  /** 订单中心的页内视图；view 缺省即订单查询，旧深链因此完全不受影响。 */
  type OrderCenterView = 'order' | 'exception' | 'aftersale'

  const VIEW_KEYS: OrderCenterView[] = ['order', 'exception', 'aftersale']

  const readQueryValue = (value: unknown): string => {
    const raw = Array.isArray(value) ? value[0] : value
    return typeof raw === 'string' ? raw : ''
  }

  const readPositiveQueryNumber = (value: unknown): number | undefined => {
    const parsed = Number(readQueryValue(value))
    return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
  }

  const readView = (value: unknown): OrderCenterView => {
    const raw = readQueryValue(value)
    return (VIEW_KEYS as string[]).includes(raw) ? (raw as OrderCenterView) : 'order'
  }

  const activeView = ref<OrderCenterView>(readView(route.query.view))
  const activeViewLabel = computed(
    () =>
      ({
        order: '订单查询',
        exception: '异常待补偿',
        aftersale: '售后台账'
      })[activeView.value]
  )
  const afterSaleKeyword = ref(readQueryValue(route.query.afterSaleKeyword))
  /** 售后台账的执行状态筛选：顶部计数下钻与台账自身的筛选共用它，深链可回放。 */
  const afterSaleActionStatus = ref<number | undefined>(
    readPositiveQueryNumber(route.query.afterSaleActionStatus)
  )

  let applyingFilters = false
  const searchForm = ref<{
    orderNo?: string
    userKeyword?: string
    orderType?: number
    orderStatus?: number
  }>({
    orderNo: readQueryValue(route.query.orderNo) || undefined,
    userKeyword: readQueryValue(route.query.userKeyword) || undefined,
    orderType: readPositiveQueryNumber(route.query.orderType),
    // 支持总览待办卡片带参直达（?orderStatus=6 异常待补偿）
    orderStatus: readPositiveQueryNumber(route.query.orderStatus)
  })

  const traceVisible = ref(false)
  const currentOrderId = ref<string>()
  const reconcileVisible = ref(false)
  const reconcileOrderId = ref<string>()
  const rechargeRefundVisible = ref(false)
  const rechargeRefundOrderId = ref<string>()
  const rechargeRefundMode = ref<RechargeRefundEntryMode>('entitlement')
  const rechargeRefundOrderNo = ref<string>()
  const rechargeRefundOrderAmount = ref<number>()
  const ledgerRef = ref<InstanceType<typeof AfterSaleLedger> | null>(null)
  const exceptionRef = ref<InstanceType<typeof OrderExceptionQueue> | null>(null)

  // 字典（1340/1341/1346）
  const orderTypeOptions = ref<{ label: string; value: number }[]>([])
  const orderStatusOptions = ref<{ label: string; value: number }[]>([])
  const payWayOptions = ref<{ label: string; value: number }[]>([])

  /**
   * 筛选项＝服务端 AdminOrderBo 当前支持的等值/模糊条件，一一对应。
   * 下单时间区间与按用户ID 精确查暂不在此：AdminOrderBo 无对应字段，端点会静默忽略，
   * 前端摆一个不生效的筛选框比没有更糟（运营会以为已经按日期筛过）。补齐需后端同轮开条件。
   */
  const searchItems = computed(() => [
    { label: '订单号', key: 'orderNo', type: 'input', placeholder: '订单号', clearable: true },
    {
      label: '使用人',
      key: 'userKeyword',
      type: 'input',
      placeholder: '姓名或手机号',
      clearable: true
    },
    {
      label: '订单类型',
      key: 'orderType',
      type: 'select',
      placeholder: '全部类型',
      clearable: true,
      options: orderTypeOptions.value
    },
    {
      label: '订单状态',
      key: 'orderStatus',
      type: 'select',
      placeholder: '全部状态',
      clearable: true,
      options: orderStatusOptions.value
    }
  ])

  const orderTypeLabel = (v: number) =>
    orderTypeOptions.value.find((o) => o.value === v)?.label || String(v)
  const orderStatusLabel = (v: number) =>
    orderStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const payWayLabel = (v: number) =>
    payWayOptions.value.find((o) => o.value === v)?.label || String(v)

  /**
   * 充值单的支付方式必须以该订单的支付记录为准，不能仅凭 payWay=1 推断为微信支付。
   * 非充值订单继续沿用支付方式字典，不改变既有展示口径。
   */
  const orderPaymentLabel = (order: OrderItem): string => {
    if (order.orderType !== 2 || order.payWay !== 1) return payWayLabel(order.payWay)
    const evidenceState = paymentEvidenceStateOf(order)
    if (evidenceState === 'invalid') return '支付记录异常'
    if (evidenceState === 'missing') return '无支付记录'
    if (order.paySource === 2) return '模拟支付'
    if (order.paySource === 1) return '微信支付'
    return '支付来源缺失'
  }

  /** 状态→色调：完成绿 / 进行蓝 / 异常橙 / 退款取消灰红 */
  const orderStatusTagType = (v: number) =>
    v === 4
      ? 'success'
      : v === 6
        ? 'warning'
        : v === 5 || v === 7 || v === 8
          ? 'danger'
          : v === 3
            ? 'primary'
            : 'info'

  const formatTime = (t?: string) => {
    if (!t || t.length !== 14) return t || '-'
    return `${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}`
  }

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    handleSizeChange,
    handleCurrentChange
  } = useTable({
    core: {
      apiFn: fetchOrderPage,
      apiParams: { current: 1, size: 20 },
      immediate: false,
      columnsFactory: () => [
        { prop: 'orderNo', label: '订单号', minWidth: 170, fixed: 'left' },
        {
          prop: 'orderType',
          label: '类型',
          width: 95,
          formatter: (row: OrderItem) =>
            h(
              ElTag,
              {
                type: row.orderType === 1 ? 'primary' : row.orderType === 2 ? 'success' : 'warning'
              },
              () => orderTypeLabel(row.orderType)
            )
        },
        { prop: 'userName', label: '使用人', minWidth: 150, useSlot: true },
        { prop: 'stationName', label: '站点 / 设备', minWidth: 180, useSlot: true },
        {
          prop: 'orderAmount',
          label: '金额',
          width: 100,
          align: 'right',
          formatter: (row: OrderItem) => `￥${fenToYuan(row.orderAmount)}`
        },
        {
          prop: 'payWay',
          label: '支付方式',
          width: 120,
          formatter: (row: OrderItem) => orderPaymentLabel(row)
        },
        { prop: 'planMl', label: '水量(计划→实际)', width: 150, useSlot: true },
        {
          prop: 'orderStatus',
          label: '状态',
          minWidth: 150,
          formatter: (row: OrderItem) =>
            h(ElTag, { type: orderStatusTagType(row.orderStatus) }, () =>
              orderStatusLabel(row.orderStatus)
            )
        },
        {
          prop: 'createTime',
          label: '下单时间',
          width: 150,
          formatter: (row: OrderItem) => formatTime(row.createTime)
        },
        { prop: 'operation', label: '操作', width: 230, fixed: 'right', useSlot: true }
      ]
    }
  })

  const pageAmountYuan = computed(() =>
    fenToYuan((data.value as OrderItem[]).reduce((sum, row) => sum + row.orderAmount, 0))
  )

  const orderExportFilename = currentPageExportFilename('订单查询')
  const orderExportRows = computed(() =>
    currentPageExportRows<OrderItem>(data.value as OrderItem[], [
      { header: '订单号', value: (row) => row.orderNo },
      { header: '类型', value: (row) => orderTypeLabel(row.orderType) },
      { header: '使用人', value: (row) => row.userName || '' },
      { header: '手机号', value: (row) => row.actorMaskedPhone || '' },
      { header: '站点', value: (row) => row.stationName || '' },
      { header: '设备号', value: (row) => row.deviceNo || '' },
      { header: '金额(元)', value: (row) => fenToYuan(row.orderAmount) },
      { header: '支付方式', value: (row) => orderPaymentLabel(row) },
      { header: '计划水量', value: (row) => (row.planMl ? mlToLiter(row.planMl) : '') },
      { header: '实际水量', value: (row) => (row.actualMl != null ? mlToLiter(row.actualMl) : '') },
      { header: '状态', value: (row) => orderStatusLabel(row.orderStatus) },
      { header: '下单时间', value: (row) => formatTime(row.createTime) }
    ])
  )

  const goUserProfile = (userId: string) => {
    router.push({ path: '/user/index', query: { userId } })
  }

  function loadData() {
    replaceSearchParams({
      orderNo: searchForm.value.orderNo || undefined,
      userKeyword: searchForm.value.userKeyword || undefined,
      orderType: searchForm.value.orderType,
      orderStatus: searchForm.value.orderStatus
    })
    return getData()
  }

  // ==== 待办计数（只读；每格与它所指向的那张列表同端点同筛选） ====
  interface QueueMetric {
    key: string
    label: string
    view: OrderCenterView
    /** 售后类指标专属：该格统计的是这个执行状态的动作条数，下钻带的也是同一个值。 */
    actionStatus?: number
    /** 计数单位，随计数一起展示。 */
    unit: string
    icon: string
    iconStyle: string
    count?: number
    /** 取不到数时如实说明，不落 0——0 会被当成「这批已经清空」。 */
    unavailable?: string
  }

  const metrics = ref<QueueMetric[]>([])

  /**
   * 售后各格按 AFTER_SALE_TODO_STATUSES 展开：待办口径只有那一份归组定义，
   * 页面再切一套就会出现落在所有格子之外的状态（动作停在那个态时顶部全是 0，队列看着已清空）。
   * 状态名一律取字典 1372，取不到字典就不出售后格——宁可少一格，也不在页面上另写一份状态文案。
   */
  function buildMetrics(actionStatuses: { label: string; value: number }[]) {
    const exception: QueueMetric = {
      key: 'exception',
      label: '异常待补偿',
      view: 'exception',
      unit: '单',
      icon: 'ri:error-warning-line',
      iconStyle: 'bg-warning'
    }
    const afterSale = AFTER_SALE_TODO_STATUSES.flatMap<QueueMetric>((status) => {
      const label = actionStatuses.find((item) => item.value === status)?.label
      if (!label) return []
      return [
        {
          key: `afterSale-${status}`,
          label: `售后${label}`,
          view: 'aftersale',
          actionStatus: status,
          unit: '笔',
          icon: 'ri:refund-2-line',
          iconStyle:
            status === AfterSaleActionStatus.RECONCILIATION_REQUIRED ? 'bg-danger' : 'bg-primary'
        }
      ]
    })
    metrics.value = [exception, ...afterSale]
  }

  /** 选中态＝这一格的筛选正是当前列表在用的那个，不只看视图。 */
  const isMetricActive = (metric: QueueMetric) =>
    activeView.value === metric.view &&
    (metric.actionStatus == null || afterSaleActionStatus.value === metric.actionStatus)

  const metricDescription = (metric: QueueMetric) => metric.unavailable ?? metric.unit

  /**
   * 计数取各自列表查询的总数（每页 1 条）。失败或无权限就留空并说明，
   * 显示一个来路不明的数字比不显示更糟——运营会照着它去追不存在的单子。
   */
  async function loadMetrics() {
    await Promise.all(
      metrics.value.map(async (metric) => {
        try {
          if (metric.actionStatus == null) {
            const res = await fetchOrderPage({
              current: 1,
              size: 1,
              orderStatus: ORDER_STATUS_ABNORMAL
            })
            metric.count = res.total
            metric.unavailable = undefined
            return
          }
          if (!canQueryAfterSale.value) {
            metric.count = undefined
            metric.unavailable = '无查询权限'
            return
          }
          const res = await fetchAfterSaleActionPage({
            current: 1,
            size: 1,
            actionStatus: metric.actionStatus
          })
          metric.count = res.total
          metric.unavailable = undefined
        } catch {
          metric.count = undefined
          metric.unavailable = '暂时取不到'
        }
      })
    )
  }

  onMounted(async () => {
    const [types, statuses, payWays, actionStatuses] = await Promise.all([
      fetchDictOptions(DictTypeEnum.订单类型),
      fetchDictOptions(DictTypeEnum.订单状态),
      fetchDictOptions(DictTypeEnum.支付方式),
      fetchDictOptions(DictTypeEnum.售后执行状态)
    ])
    orderTypeOptions.value = toDictOptions(types)
    orderStatusOptions.value = toDictOptions(statuses)
    payWayOptions.value = toDictOptions(payWays)
    buildMetrics(toDictOptions(actionStatuses))
    if (activeView.value === 'order') await loadData()
    openTraceFromQuery()
    await loadMetrics()
  })

  watch(
    () => route.fullPath,
    () => {
      if (route.path !== '/order/index') return
      activeView.value = readView(route.query.view)
      afterSaleKeyword.value = readQueryValue(route.query.afterSaleKeyword)
      afterSaleActionStatus.value = readPositiveQueryNumber(route.query.afterSaleActionStatus)
      // 队列视图的地址栏里本就没有订单筛选参数，此时回写等于把运营的筛选条件清空，
      // 「返回订单查询」就会退到一张全量表；只有订单查询视图才以地址栏为准。
      if (activeView.value === 'order') {
        searchForm.value = {
          orderNo: readQueryValue(route.query.orderNo) || undefined,
          userKeyword: readQueryValue(route.query.userKeyword) || undefined,
          orderType: readPositiveQueryNumber(route.query.orderType),
          orderStatus: readPositiveQueryNumber(route.query.orderStatus)
        }
      }
      if (applyingFilters) return
      // 非订单查询视图不重复拉订单分页；追溯深链在所有视图都要能打开
      if (activeView.value !== 'order') {
        openTraceFromQuery()
        return
      }
      loadData().then(openTraceFromQuery)
    }
  )

  /** 当前订单查询条件（用于视图切换时保留筛选，不与售后关键字互相污染）。 */
  function orderQuery(): Record<string, string> {
    return {
      ...(searchForm.value.orderType ? { orderType: String(searchForm.value.orderType) } : {}),
      ...(searchForm.value.orderNo ? { orderNo: searchForm.value.orderNo } : {}),
      ...(searchForm.value.userKeyword ? { userKeyword: searchForm.value.userKeyword } : {}),
      ...(searchForm.value.orderStatus ? { orderStatus: String(searchForm.value.orderStatus) } : {})
    }
  }

  async function replaceQuery(query: Record<string, string>) {
    if (router.resolve({ path: route.path, query }).fullPath === route.fullPath) return false
    await router.replace({ path: route.path, query })
    return true
  }

  /**
   * 筛选变化：先把 applyingFilters 立起来再改地址栏。
   * 顺序反了地址栏 watcher 会抢先跑一次 loadData，同一次筛选打两遍接口。
   */
  async function applyFilters() {
    const query = orderQuery()
    if (router.resolve({ path: route.path, query }).fullPath === route.fullPath) {
      await loadData()
      return
    }
    applyingFilters = true
    try {
      await router.replace({ path: route.path, query })
      await nextTick()
      await loadData()
      openTraceFromQuery()
    } finally {
      applyingFilters = false
    }
  }

  async function handleReset() {
    searchForm.value = {}
    await applyFilters()
  }

  async function handleRefresh() {
    await loadData()
    await loadMetrics()
  }

  /**
   * 视图切换写进地址栏，保证刷新与后退回到同一视图。
   * 切到订单查询时把订单筛选条件带回去，切到售后视图时只保留售后参数，
   * 两组参数互不污染（否则订单号会以关键字身份出现在售后台账的搜索框里）。
   */
  function queryForView(view: OrderCenterView): Record<string, string> {
    if (view === 'order') return orderQuery()
    if (view === 'exception') return { view }
    return {
      view,
      ...(afterSaleKeyword.value ? { afterSaleKeyword: afterSaleKeyword.value } : {}),
      ...(afterSaleActionStatus.value != null
        ? { afterSaleActionStatus: String(afterSaleActionStatus.value) }
        : {})
    }
  }

  async function handleViewChange(view: string | number | boolean | undefined) {
    const target = readView(view)
    activeView.value = target
    await replaceQuery(queryForView(target))
  }

  /** 队列的返回路径：回到订单查询并带回原筛选。 */
  async function backToOrderList() {
    activeView.value = 'order'
    if (!(await replaceQuery(orderQuery()))) await loadData()
  }

  /**
   * 指标下钻：落地的必须是产出这个数字的同一次查询。
   * 异常待补偿进入的队列本身就钉死 orderStatus=6；售后各格把自己那个执行状态带进台账，
   * 并清掉关键字（计数只带执行状态一个条件，台账多留一个条件，数字就和列表对不上了）。
   * 不能只靠地址栏传递：三格的路径在已处于售后视图时可能完全相同，replaceQuery 会直接返回。
   */
  async function openMetric(metric: QueueMetric) {
    activeView.value = metric.view
    if (metric.view === 'aftersale') {
      afterSaleKeyword.value = ''
      afterSaleActionStatus.value = metric.actionStatus
    }
    await replaceQuery(queryForView(metric.view))
  }

  function handleAfterSaleKeywordChange(keyword: string) {
    afterSaleKeyword.value = keyword
    if (activeView.value !== 'aftersale') return
    replaceQuery(queryForView('aftersale'))
  }

  /** 台账里改执行状态同样回写地址栏：深链可回放，顶部选中态也跟着走。 */
  function handleAfterSaleActionStatusChange(actionStatus?: number) {
    afterSaleActionStatus.value = actionStatus
    if (activeView.value !== 'aftersale') return
    replaceQuery(queryForView('aftersale'))
  }

  /** 取水核账入口：只打开只读预览，是否可确认由服务端决定。 */
  function openWaterReconcile(row: OrderItem) {
    reconcileOrderId.value = row.id
    nextTick(() => {
      reconcileVisible.value = true
    })
  }

  /**
   * 核账确认会推进订单终态，并在售后台账里留下一条来源=取水异常核账的动作。
   * 各视图都要回表刷新，否则运营切换回来时看到的是确认之前的旧快照。
   */
  async function handleReconcileDone() {
    await loadData()
    await ledgerRef.value?.reload()
    await exceptionRef.value?.reload()
    await loadMetrics()
  }

  /** 充值退款入口：只打开只读预览，是否可退由服务端决定。 */
  function openRechargeRefund(row: OrderItem) {
    const mode = rechargeRefundEntryMode(row)
    if (!mode) return
    rechargeRefundOrderId.value = row.id
    rechargeRefundMode.value = mode
    rechargeRefundOrderNo.value = row.orderNo
    rechargeRefundOrderAmount.value = row.orderAmount
    nextTick(() => {
      rechargeRefundVisible.value = true
    })
  }

  /**
   * 受理成功只意味着退款单已建立并发起：订单终态与卡权益要等事实回签，各视图都刷新
   * 让运营在台账里看到那条来源=充值退款的待执行动作。
   */
  async function handleRechargeRefundDone() {
    await loadData()
    await ledgerRef.value?.reload()
    await exceptionRef.value?.reload()
    await loadMetrics()
  }

  function openTraceById(orderId: string) {
    currentOrderId.value = orderId
    nextTick(() => {
      traceVisible.value = true
    })
  }

  /**
   * 深链打开追溯：不依赖当前分页是否包含该 ID，直接按 traceOrderId 请求追溯，
   * 由抽屉呈现结果或错误。
   */
  function openTraceFromQuery() {
    const raw = route.query.traceOrderId
    const orderId = typeof raw === 'string' && /^\d+$/.test(raw) ? raw : undefined
    if (!orderId) return
    openTraceById(orderId)
  }
</script>

<style scoped>
  .order-overview {
    flex: 0 0 auto;
    padding: 12px 14px 14px;
    margin-bottom: 12px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 10px;
  }

  .order-overview__header,
  .order-view-switch__inner {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .order-overview__header {
    margin-bottom: 8px;
  }

  .order-overview__title {
    font-size: 14px;
    font-weight: 650;
    line-height: 20px;
    color: var(--el-text-color-primary);
  }

  .order-view-switch__eyebrow {
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-secondary);
  }

  .order-overview__grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(168px, 1fr));
    gap: 8px;
  }

  .order-overview__metric {
    position: relative;
    display: grid;
    grid-template-columns: 32px minmax(0, 1fr) auto 16px;
    gap: 9px;
    align-items: center;
    min-height: 58px;
    padding: 9px 11px;
    font: inherit;
    color: inherit;
    text-align: left;
    cursor: pointer;
    background: var(--el-fill-color-blank);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
    transition:
      border-color 0.18s ease,
      box-shadow 0.18s ease,
      transform 0.18s ease;
  }

  .order-overview__metric:hover {
    border-color: var(--el-color-primary-light-5);
    box-shadow: var(--el-box-shadow-lighter);
    transform: translateY(-1px);
  }

  .order-overview__metric.is-active {
    background: var(--el-color-primary-light-9);
    border-color: var(--el-color-primary-light-5);
  }

  .order-overview__metric:focus-visible {
    outline: 2px solid var(--el-color-primary);
    outline-offset: 2px;
  }

  .order-overview__icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    font-size: 16px;
    border-radius: 7px;
  }

  .order-overview__icon.bg-primary {
    color: var(--el-color-primary);
    background: var(--el-color-primary-light-9);
  }

  .order-overview__icon.bg-warning {
    color: var(--el-color-warning);
    background: var(--el-color-warning-light-9);
  }

  .order-overview__icon.bg-danger {
    color: var(--el-color-danger);
    background: var(--el-color-danger-light-9);
  }

  .order-overview__value {
    display: flex;
  }

  .order-overview__label {
    overflow: hidden;
    font-size: 13px;
    font-weight: 600;
    line-height: 18px;
    color: var(--el-text-color-primary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .order-overview__value {
    gap: 4px;
    align-items: baseline;
    white-space: nowrap;
  }

  .order-overview__value strong {
    font-size: 20px;
    font-weight: 650;
    line-height: 22px;
    color: var(--el-text-color-primary);
  }

  .order-overview__value small {
    font-size: 11px;
    color: var(--el-text-color-secondary);
  }

  .order-overview__arrow {
    font-size: 16px;
    color: var(--el-text-color-placeholder);
  }

  .order-view-switch {
    flex: 0 0 auto;
    margin-bottom: 12px;
    border-color: var(--el-border-color-lighter);
  }

  .order-view-switch :deep(.el-card__body) {
    height: auto;
    padding: 10px 14px;
    overflow: visible;
  }

  .order-view-switch__inner {
    gap: 14px;
    justify-content: flex-start;
  }

  .order-view-switch__heading {
    display: flex;
    flex-direction: column;
    min-width: 112px;
  }

  .order-view-switch__current {
    font-size: 13px;
    font-weight: 650;
    line-height: 18px;
    color: var(--el-text-color-primary);
  }

  .table-summary-amount {
    color: var(--el-text-color-secondary);
  }

  .export-button-content,
  .user-link {
    display: inline-flex;
    align-items: center;
  }

  .export-button-content {
    gap: 6px;
  }

  .user-link {
    height: auto;
    padding: 0;
    vertical-align: baseline;
  }

  @media (width <= 980px) {
    .order-overview__grid {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
  }

  @media (width <= 680px) {
    .order-overview__grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .order-view-switch__heading {
      display: none;
    }

    .order-view-switch__inner {
      align-items: flex-start;
      overflow-x: auto;
    }
  }
</style>
