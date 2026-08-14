<!-- 分账明细（E2E-08 包E）：账务计提口径（Pay-Sim 环境记账不动钱），只读。
     本页零写入口——「这页不可写」由结构表达，不靠按钮隐藏；共键（订单号）打通到订单追溯与售后台账。 -->
<template>
  <div class="art-full-height">
    <BusinessModuleNav module-key="finance" />

    <ElAlert type="info" :closable="false" show-icon title="分账为账务计提金额" class="mb-3" />
    <!-- 全仓统一搜索形态：auto-search 任一字段变更即防抖查询，无独立查询按钮 -->
    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      auto-search
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="art-table-card">
      <!-- 四视图只是既有分账状态字典值的归并：每次查询下发的仍是字典里的单个状态值 -->
      <div class="queue-bar">
        <ElRadioGroup v-model="queueKey" @change="handleQueueChange">
          <ElRadioButton v-for="queue in SPLIT_QUEUES" :key="queue.key" :value="queue.key">
            {{ queue.label }}
          </ElRadioButton>
        </ElRadioGroup>
        <ElRadioGroup
          v-if="subStatusOptions.length > 1"
          v-model="statusFilter"
          size="small"
          @change="handleSearch"
        >
          <ElRadioButton v-for="opt in subStatusOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </ElRadioButton>
        </ElRadioGroup>
        <span class="hint-text">{{ queueHint }}</span>
      </div>

      <ElAlert
        v-if="orderNoMissing"
        class="mb-3"
        type="warning"
        :closable="false"
        show-icon
        title="没有找到这个订单号"
      />

      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <BusinessTableSummary :total="pagination.total" :page-size="data.length">
            <span class="table-summary-amount">本页计提 ￥{{ pageSplitAmountYuan }}</span>
          </BusinessTableSummary>
        </template>
        <template #right>
          <ArtExcelExport
            :data="splitExportRows"
            :filename="splitExportFilename"
            sheet-name="分账明细"
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
      </ArtTable>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import BusinessTableSummary from '@/components/business/business-table-summary/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import {
    fetchSplitPage,
    financeQueueOf,
    resolveOrderIdByNo,
    SPLIT_QUEUES,
    type FinanceQueueKey,
    type SplitRecordItem
  } from '@/api/finance'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { bpToPercentText, fenToYuan } from '@/utils/format'
  import { ElAlert, ElButton, ElRadioButton, ElRadioGroup, ElTag } from 'element-plus'
  import dayjs from 'dayjs'
  import { currentPageExportFilename, currentPageExportRows } from '@/utils/current-page-export'

  defineOptions({ name: 'OrderSplit' })

  const route = useRoute()
  const router = useRouter()

  const readQueryValue = (value: unknown): string => {
    const raw = Array.isArray(value) ? value[0] : value
    return typeof raw === 'string' ? raw : ''
  }

  const searchForm = ref<{ orderNo?: string; orderId?: string; receiverType?: number }>({
    orderNo: readQueryValue(route.query.orderNo) || undefined
  })
  const receiverOptions = ref<{ label: string; value: number }[]>([])
  const statusOptions = ref<{ label: string; value: number }[]>([])

  /** 当前视图与视图内的状态。视图切换时状态跟着落到该视图的首个字典值。 */
  const queueKey = ref<FinanceQueueKey>('fact')
  const statusFilter = ref<number | undefined>(undefined)
  /** 订单号填了但库里没有这个单：如实置空列表并提示，不能回落成全量。 */
  const orderNoMissing = ref(false)

  const currentQueue = computed(() => financeQueueOf(SPLIT_QUEUES, queueKey.value))

  /** 视图内可选状态，标签取自字典，页面不写第二份状态文案。 */
  const subStatusOptions = computed(() =>
    currentQueue.value.statuses.map((status) => ({
      value: status,
      label: dictLabel(statusOptions.value, status)
    }))
  )

  /**
   * 视图说明必须说清楚表里到底是哪一批：服务端一次只按一个状态值筛，
   * 而「已完成」这类视图归并了两个状态值（已分账 + 已回退），只摆视图说明会让人以为回退的记录也在表里
   * ——核对某单是否完成分账时，一笔已被冲减回退的分账会被读成「已完成里没有这笔」。
   */
  const queueHint = computed(() => {
    const queue = currentQueue.value
    if (queue.statuses.length <= 1 || statusFilter.value == null) return queue.hint
    const label = statusOptions.value.find((item) => item.value === statusFilter.value)?.label
    return label ? `${queue.hint} · 当前只看${label}` : queue.hint
  })

  const searchItems = computed(() => [
    { label: '订单号', key: 'orderNo', type: 'input', placeholder: '订单号', clearable: true },
    { label: '订单ID', key: 'orderId', type: 'input', placeholder: '订单ID', clearable: true },
    {
      label: '收款方',
      key: 'receiverType',
      type: 'select',
      placeholder: '收款方',
      clearable: true,
      options: receiverOptions.value
    }
  ])

  onMounted(async () => {
    const [receivers, statuses] = await Promise.all([
      fetchDictOptions(DictTypeEnum.分账收款方类型),
      fetchDictOptions(DictTypeEnum.分账状态)
    ])
    receiverOptions.value = toDictOptions(receivers)
    statusOptions.value = toDictOptions(statuses)
    if (searchForm.value.orderNo) await handleSearch()
  })

  /** 从售后台账等页面带订单号直达时刷新条件；本页地址栏只认 orderNo 一个参数。 */
  watch(
    () => route.fullPath,
    () => {
      if (route.path !== '/order/split') return
      const orderNo = readQueryValue(route.query.orderNo) || undefined
      if (orderNo === searchForm.value.orderNo) return
      searchForm.value.orderNo = orderNo
      handleSearch()
    }
  )

  const dictLabel = (options: { label: string; value: number }[], value: number) =>
    options.find((o) => o.value === value)?.label ?? String(value)

  const formatTime = (t?: string) =>
    t && t.length === 14 ? dayjs(t, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss') : '—'

  /** 追溯与售后台账都按订单号/订单ID 共键直达，运营不必再自己复制单号换页搜。 */
  const goTrace = (orderId?: string) => {
    if (!orderId) return
    router.push({ path: '/order/index', query: { traceOrderId: String(orderId) } })
  }

  const goAfterSale = (orderNo?: string) => {
    if (!orderNo) return
    router.push({
      path: '/order/index',
      query: { view: 'aftersale', afterSaleKeyword: orderNo }
    })
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
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchSplitPage,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        {
          prop: 'splitRemark',
          label: '关联订单',
          minWidth: 210,
          fixed: 'left',
          // SPLIT_REMARK 恒存订单号快照（非自由备注），点击即进入该单全链路追溯
          formatter: (row: SplitRecordItem) =>
            h('div', [
              row.splitRemark
                ? h(
                    ElButton,
                    {
                      type: 'primary',
                      link: true,
                      class: 'link-cell',
                      onClick: () => goTrace(row.orderId)
                    },
                    () => row.splitRemark
                  )
                : h('span', '—'),
              h('div', { class: 'sub-text' }, `订单 ${row.orderId || '—'}`)
            ])
        },
        {
          prop: 'receiverType',
          label: '收款方',
          width: 110,
          formatter: (row: SplitRecordItem) => dictLabel(receiverOptions.value, row.receiverType)
        },
        {
          prop: 'splitAmount',
          label: '分账金额',
          width: 120,
          formatter: (row: SplitRecordItem) => `¥${fenToYuan(row.splitAmount)}`
        },
        {
          prop: 'splitRateSnap',
          label: '比例快照',
          width: 110,
          // REMAINDER=平台余数行（整除余数恒归平台，全行合计=基数）
          formatter: (row: SplitRecordItem) =>
            row.splitRateSnap === 'REMAINDER' ? '余数归平台' : bpToPercentText(row.splitRateSnap)
        },
        {
          prop: 'splitStatus',
          label: '状态',
          width: 100,
          formatter: (row: SplitRecordItem) =>
            h(
              ElTag,
              {
                type:
                  row.splitStatus === 2 ? 'success' : row.splitStatus === 4 ? 'danger' : 'warning'
              },
              () => dictLabel(statusOptions.value, row.splitStatus)
            )
        },
        {
          prop: 'reversedAmount',
          label: '退款冲减',
          width: 150,
          // 冲减证据（D-420）：已回退行为全额，分线行只冲水费份额（配送费份额保留）
          formatter: (row: SplitRecordItem) =>
            row.reversedAmount && row.reversedAmount > 0
              ? h('div', [
                  h(ElTag, { type: 'danger' }, () => `-¥${fenToYuan(row.reversedAmount!)}`),
                  h('div', { class: 'sub-text' }, `退款单 ${row.refundId || '—'}`)
                ])
              : '—'
        },
        {
          prop: 'splitTime',
          label: '分账时间',
          width: 170,
          formatter: (row: SplitRecordItem) => formatTime(row.splitTime)
        },
        {
          prop: 'traceOps',
          label: '追溯',
          width: 165,
          fixed: 'right',
          formatter: (row: SplitRecordItem) =>
            h('div', { class: 'row-ops' }, [
              h(
                ElButton,
                {
                  type: 'primary',
                  link: true,
                  size: 'small',
                  disabled: !row.orderId,
                  onClick: () => goTrace(row.orderId)
                },
                () => '订单追溯'
              ),
              h(
                ElButton,
                {
                  type: 'primary',
                  link: true,
                  size: 'small',
                  disabled: !row.splitRemark,
                  onClick: () => goAfterSale(row.splitRemark)
                },
                () => '退款与补偿'
              )
            ])
        }
      ]
    }
  })

  const pageSplitAmountYuan = computed(() =>
    fenToYuan((data.value as SplitRecordItem[]).reduce((sum, row) => sum + row.splitAmount, 0))
  )

  const splitExportFilename = currentPageExportFilename('分账明细')
  const splitExportRows = computed(() =>
    currentPageExportRows<SplitRecordItem>(data.value as SplitRecordItem[], [
      { header: '订单号', value: (row) => row.splitRemark || '' },
      { header: '订单ID', value: (row) => row.orderId || '' },
      { header: '收款方', value: (row) => dictLabel(receiverOptions.value, row.receiverType) },
      { header: '分账金额(元)', value: (row) => fenToYuan(row.splitAmount) },
      {
        header: '比例快照',
        value: (row) =>
          row.splitRateSnap === 'REMAINDER' ? '余数归平台' : bpToPercentText(row.splitRateSnap)
      },
      { header: '状态', value: (row) => dictLabel(statusOptions.value, row.splitStatus) },
      { header: '退款冲减(元)', value: (row) => fenToYuan(row.reversedAmount || 0) },
      { header: '退款单ID', value: (row) => row.refundId || '' },
      { header: '分账时间', value: (row) => formatTime(row.splitTime) }
    ])
  )

  /**
   * 请求代际：本页查询是两跳（订单号 → 订单ID 要先发一次订单分页），而搜索栏是 350ms 防抖自动查询，
   * 运营把订单号补全的过程中会连着触发两次，两条链同时在飞。不比代际的话，先发的那次
   * （前缀查不到单）后回包就会把「没有找到这个订单号」盖到已经填全的订单号上，
   * 页面于是对一个真实存在的单子显示空分账列表——资金证据面上这是错误结论。
   */
  let searchSequence = 0

  /**
   * 订单号是运营手上的键，分账按订单ID 索引：先逐字解析成订单ID 再查。
   * 解析不到时用不存在的订单ID 占位，让列表如实为空——回落成不带条件的全量会让人以为查的是本单。
   */
  const handleSearch = async () => {
    const sequence = ++searchSequence
    const orderNo = searchForm.value.orderNo?.trim()
    let orderId = searchForm.value.orderId?.trim() || undefined
    orderNoMissing.value = false
    if (orderNo) {
      const resolved = await resolveOrderIdByNo(orderNo)
      if (sequence !== searchSequence) return
      if (resolved) {
        orderId = resolved
      } else {
        orderNoMissing.value = true
        orderId = '0'
      }
    }
    replaceSearchParams({
      orderId,
      receiverType: searchForm.value.receiverType,
      splitStatus: statusFilter.value
    })
    await getData()
  }

  /** 切视图＝换成该视图的首个字典状态；业务事实视图不带状态条件。 */
  const handleQueueChange = () => {
    statusFilter.value = currentQueue.value.statuses[0]
    handleSearch()
  }

  const handleReset = () => {
    // 同样要进代际：在飞的那次订单号解析回来后还会写一次查询条件，把刚清空的筛选又填回去
    searchSequence += 1
    searchForm.value = {}
    queueKey.value = 'fact'
    statusFilter.value = undefined
    orderNoMissing.value = false
    replaceSearchParams({})
    getData()
  }
</script>

<style scoped>
  .queue-bar {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: center;
    margin-bottom: 12px;
  }

  .hint-text {
    font-size: 12px;
    color: var(--art-text-gray-500);
  }

  .table-summary-amount {
    color: var(--el-text-color-secondary);
  }

  .export-button-content {
    display: inline-flex;
    gap: 6px;
    align-items: center;
  }

  :deep(.sub-text) {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  :deep(.link-cell) {
    height: auto;
    padding: 0;
  }

  :deep(.row-ops) {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }
</style>
