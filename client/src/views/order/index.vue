<!-- 订单中心：取水/充值/配送三类订单 + 全链路追溯（REQ-022/050）。 -->
<template>
  <div class="order-page art-full-height">
    <BusinessModuleNav module-key="order" />

    <ElCard class="art-table-card" shadow="never">
      <!-- 搜索行 -->
      <div class="mb-3 flex flex-wrap items-center gap-3">
        <ElRadioGroup v-model="orderTypeFilter" @change="applyFilters">
          <ElRadioButton value="all">全部类型</ElRadioButton>
          <ElRadioButton value="1">扫码取水</ElRadioButton>
          <ElRadioButton value="2">购卡充值</ElRadioButton>
          <ElRadioButton value="3">水配送订单</ElRadioButton>
        </ElRadioGroup>
        <ElInput
          v-model="searchForm.orderNo"
          placeholder="订单号"
          clearable
          style="width: 220px"
          @input="applyFiltersDebounced"
        />
        <ElInput
          v-model="searchForm.userKeyword"
          placeholder="用户姓名/手机号"
          clearable
          style="width: 180px"
          @input="applyFiltersDebounced"
        />
        <ElSelect
          v-model="searchForm.orderStatus"
          placeholder="订单状态"
          clearable
          style="width: 160px"
          @change="applyFilters"
        >
          <ElOption
            v-for="opt in orderStatusOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </ElSelect>
        <ElButton @click="handleReset" v-ripple>重置</ElButton>
        <ElTag type="success" effect="plain" class="ml-auto">订单数据来自真实后端</ElTag>
        <ElTag type="info" effect="plain">异常处理为后续切片</ElTag>
      </div>

      <!-- 订单表格 -->
      <ElTable :data="list" row-key="id" border v-loading="loading">
        <ElTableColumn prop="orderNo" label="订单号" min-width="170" fixed="left" />
        <ElTableColumn label="类型" width="95">
          <template #default="{ row }">
            <ElTag
              :type="row.orderType === 1 ? 'primary' : row.orderType === 2 ? 'success' : 'warning'"
            >
              {{ orderTypeLabel(row.orderType) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <!-- 启用 CARD-MEMBER 后，ws_order.USER_ID=实际使用人（可能为授权成员），列名与语义对齐；
             手机号展示服务端脱敏结果，成员用卡时明显标注（持卡人在追溯抽屉呈现） -->
        <ElTableColumn label="使用人" min-width="150">
          <template #default="{ row }">
            {{ row.userName
            }}<template v-if="row.actorMaskedPhone">（{{ row.actorMaskedPhone }}）</template>
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
        </ElTableColumn>
        <ElTableColumn label="站点 / 设备" min-width="180">
          <template #default="{ row }">
            <template v-if="row.stationName">
              {{ row.stationName }}
              <div v-if="row.deviceNo" class="text-xs text-secondary"
                >{{ row.deviceNo }} · {{ row.outletNo }}号口</div
              >
            </template>
            <template v-else>-</template>
          </template>
        </ElTableColumn>
        <ElTableColumn label="金额" width="100" align="right">
          <template #default="{ row }">￥{{ fenToYuan(row.orderAmount) }}</template>
        </ElTableColumn>
        <ElTableColumn label="支付方式" width="120">
          <template #default="{ row }">{{ orderPaymentLabel(row) }}</template>
        </ElTableColumn>
        <ElTableColumn label="水量(计划→实际)" width="140">
          <template #default="{ row }">
            <template v-if="row.planMl">
              {{ mlToLiter(row.planMl) }} →
              <span :class="row.actualMl && row.actualMl < row.planMl ? 'text-warning' : ''">
                {{ row.actualMl != null ? mlToLiter(row.actualMl) : '…' }}
              </span>
            </template>
            <template v-else>-</template>
          </template>
        </ElTableColumn>
        <!-- 场景备注仅 Mock 演示数据携带；真实订单无 scenarioLabel，该副行自然不渲染（2026-07-20 收口轮） -->
        <ElTableColumn label="状态" min-width="170">
          <template #default="{ row }">
            <ElTag :type="orderStatusTagType(row.orderStatus)">
              {{ orderStatusLabel(row.orderStatus) }}
            </ElTag>
            <div v-if="row.scenarioLabel" class="mt-1 text-xs text-secondary">
              {{ row.scenarioLabel }}
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="下单时间" width="150">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <ElButton type="primary" size="small" link @click="showTrace(row)">全链路追溯</ElButton>
            <!-- 异常处理需真实原子退差写接口（铁律1），后续切片由后端实现；此前禁用以免 Mock 资金动作误导真单 -->
            <ElTooltip v-if="row.orderStatus === 6" content="异常处理功能建设中" placement="top">
              <span>
                <ElButton type="warning" size="small" link disabled>异常处理</ElButton>
              </span>
            </ElTooltip>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="mt-3 flex justify-end">
        <ElPagination
          v-model:current-page="pageParams.current"
          v-model:page-size="pageParams.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @change="loadData"
        />
      </div>

      <!-- 六区块全链路追溯抽屉（REQ-050：订单→指令→ACK→审计→流水/配送/申诉） -->
      <OrderTraceDrawer
        v-model:visible="traceVisible"
        :order-id="currentOrderId"
        :source="currentTraceSource"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import {
    fetchOrderPage,
    paymentEvidenceStateOf,
    type OrderItem,
    type OrderSearchParams
  } from '@/api/order'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { fenToYuan, mlToLiter } from '@/utils/format'
  import { DictTypeEnum } from '@/constants/dict'
  import OrderTraceDrawer from './modules/order-trace-drawer.vue'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'

  defineOptions({ name: 'OrderList' })

  const route = useRoute()
  const router = useRouter()

  const readQueryValue = (value: unknown): string => {
    const raw = Array.isArray(value) ? value[0] : value
    return typeof raw === 'string' ? raw : ''
  }

  const readPositiveQueryNumber = (value: unknown): number | undefined => {
    const parsed = Number(readQueryValue(value))
    return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
  }

  const queryOrderType = readPositiveQueryNumber(route.query.orderType)
  const orderTypeFilter = ref(
    queryOrderType && [1, 2, 3].includes(queryOrderType) ? String(queryOrderType) : 'all'
  )
  const loading = ref(false)
  const list = ref<OrderItem[]>([])
  const total = ref(0)
  const pageParams = reactive({ current: 1, size: 20 })
  let applyingFilters = false
  const searchForm = reactive({
    orderNo: readQueryValue(route.query.orderNo),
    userKeyword: readQueryValue(route.query.userKeyword),
    // 支持总览待办卡片带参直达（?orderStatus=6 异常待补偿）
    orderStatus: readPositiveQueryNumber(route.query.orderStatus)
  })

  const traceVisible = ref(false)
  const currentOrderId = ref<string>()
  const currentTraceSource = ref<'real' | 'mock'>('real')

  // 字典（1340/1341/1346）
  const orderTypeOptions = ref<{ label: string; value: number }[]>([])
  const orderStatusOptions = ref<{ label: string; value: number }[]>([])
  const payWayOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [types, statuses, payWays] = await Promise.all([
      fetchDictOptions(DictTypeEnum.订单类型),
      fetchDictOptions(DictTypeEnum.订单状态),
      fetchDictOptions(DictTypeEnum.支付方式)
    ])
    orderTypeOptions.value = toDictOptions(types)
    orderStatusOptions.value = toDictOptions(statuses)
    payWayOptions.value = toDictOptions(payWays)
    await loadData()
    openTraceFromQuery()
  })

  watch(
    () => route.fullPath,
    () => {
      if (route.path !== '/order/index') return
      const type = readPositiveQueryNumber(route.query.orderType)
      orderTypeFilter.value = type && [1, 2, 3].includes(type) ? String(type) : 'all'
      searchForm.orderNo = readQueryValue(route.query.orderNo)
      searchForm.userKeyword = readQueryValue(route.query.userKeyword)
      searchForm.orderStatus = readPositiveQueryNumber(route.query.orderStatus)
      pageParams.current = 1
      if (applyingFilters) return
      loadData().then(openTraceFromQuery)
    }
  )

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
    if (order.paySource === 2) return 'Pay-Sim'
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

  async function loadData() {
    loading.value = true
    try {
      const params: OrderSearchParams = {
        current: pageParams.current,
        size: pageParams.size,
        orderType: orderTypeFilter.value === 'all' ? undefined : Number(orderTypeFilter.value),
        orderNo: searchForm.orderNo || undefined,
        userKeyword: searchForm.userKeyword || undefined,
        orderStatus: searchForm.orderStatus
      }
      const res = await fetchOrderPage(params)
      list.value = res.list
      total.value = res.total
    } finally {
      loading.value = false
    }
  }

  async function applyFilters() {
    pageParams.current = 1
    const query = {
      ...(orderTypeFilter.value === 'all' ? {} : { orderType: orderTypeFilter.value }),
      ...(searchForm.orderNo ? { orderNo: searchForm.orderNo } : {}),
      ...(searchForm.userKeyword ? { userKeyword: searchForm.userKeyword } : {}),
      ...(searchForm.orderStatus ? { orderStatus: String(searchForm.orderStatus) } : {})
    }
    const targetFullPath = router.resolve({ path: route.path, query }).fullPath
    if (targetFullPath === route.fullPath) return loadData()
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

  const applyFiltersDebounced = useDebounceFn(() => applyFilters(), 350)

  async function handleReset() {
    searchForm.orderNo = ''
    searchForm.userKeyword = ''
    searchForm.orderStatus = undefined
    orderTypeFilter.value = 'all'
    pageParams.current = 1
    await applyFilters()
  }

  function showTrace(row: OrderItem) {
    currentTraceSource.value = 'real'
    currentOrderId.value = row.id
    nextTick(() => {
      traceVisible.value = true
    })
  }

  function openTraceById(orderId: string, source: 'real' | 'mock' = 'real') {
    currentTraceSource.value = source
    currentOrderId.value = orderId
    nextTick(() => {
      traceVisible.value = true
    })
  }

  /**
   * 深链打开追溯（2026-07-20 收口轮复审）：
   * - 不依赖当前分页是否包含该 ID，直接按 traceOrderId 请求追溯，由抽屉呈现结果或错误；
   * - traceSource=mock 时明确进入 Mock 演示数据源（配送/申诉等 Mock 模块跳入），与真实接口严格分流。
   */
  function openTraceFromQuery() {
    const raw = route.query.traceOrderId
    const orderId = typeof raw === 'string' && /^\d+$/.test(raw) ? raw : undefined
    if (!orderId) return
    const source = route.query.traceSource === 'mock' ? 'mock' : 'real'
    openTraceById(orderId, source)
  }
</script>

<style scoped>
  .text-secondary {
    color: var(--el-text-color-secondary);
  }

  .text-warning {
    font-weight: 600;
    color: var(--el-color-warning);
  }
</style>
