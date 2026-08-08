<!-- 分账明细（E2E-08 包E）：账务计提口径（Pay-Sim 环境记账不动钱），只读 -->
<template>
  <div class="art-full-height">
    <BusinessModuleNav module-key="order" />

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
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData" />

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
  import { useTable } from '@/hooks/core/useTable'
  import { fetchSplitPage, type SplitRecordItem } from '@/api/finance'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { bpToPercentText, fenToYuan } from '@/utils/format'
  import { ElAlert, ElTag } from 'element-plus'
  import dayjs from 'dayjs'

  defineOptions({ name: 'OrderSplit' })

  const searchForm = ref<{ orderId?: string; receiverType?: number; splitStatus?: number }>({})
  const receiverOptions = ref<{ label: string; value: number }[]>([])
  const statusOptions = ref<{ label: string; value: number }[]>([])

  const searchItems = computed(() => [
    { label: '订单ID', key: 'orderId', type: 'input', placeholder: '订单ID', clearable: true },
    {
      label: '收款方',
      key: 'receiverType',
      type: 'select',
      placeholder: '收款方',
      clearable: true,
      options: receiverOptions.value
    },
    {
      label: '分账状态',
      key: 'splitStatus',
      type: 'select',
      placeholder: '分账状态',
      clearable: true,
      options: statusOptions.value
    }
  ])

  onMounted(async () => {
    const [receivers, statuses] = await Promise.all([
      fetchDictOptions(DictTypeEnum.分账收款方类型),
      fetchDictOptions(DictTypeEnum.分账状态)
    ])
    receiverOptions.value = toDictOptions(receivers)
    statusOptions.value = toDictOptions(statuses)
  })

  const dictLabel = (options: { label: string; value: number }[], value: number) =>
    options.find((o) => o.value === value)?.label ?? String(value)

  const formatTime = (t?: string) =>
    t && t.length === 14 ? dayjs(t, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss') : '—'

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
        { prop: 'id', label: 'ID', width: 80 },
        { prop: 'orderId', label: '订单ID', width: 100 },
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
          width: 130,
          // 冲减证据（D-420）：已回退行为全额，分线行只冲水费份额（配送费份额保留）
          formatter: (row: SplitRecordItem) =>
            row.reversedAmount && row.reversedAmount > 0
              ? h(ElTag, { type: 'danger' }, () => `-¥${fenToYuan(row.reversedAmount!)}`)
              : '—'
        },
        {
          prop: 'refundId',
          label: '关联退款单',
          width: 110,
          formatter: (row: SplitRecordItem) => (row.refundId ? String(row.refundId) : '—')
        },
        {
          prop: 'splitTime',
          label: '分账时间',
          width: 170,
          formatter: (row: SplitRecordItem) => formatTime(row.splitTime)
        },
        { prop: 'splitRemark', label: '订单号', minWidth: 200 }
      ]
    }
  })

  const handleSearch = () => {
    replaceSearchParams({ ...searchForm.value })
    getData()
  }

  const handleReset = () => {
    searchForm.value = {}
    replaceSearchParams({})
    getData()
  }
</script>
