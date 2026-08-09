<!-- 自动补货规则（S2）：只读——客服追踪用户规则与状态；启停与取消是用户小程序自助动作，后台不代操作 -->
<template>
  <div class="art-full-height">
    <BusinessModuleNav module-key="order" />

    <ElAlert
      type="info"
      :closable="false"
      show-icon
      title="规则的暂停、恢复与取消由用户在小程序自助完成；执行结果可按期次订单号在订单查询追溯"
      class="mb-3"
    />
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
  import { fetchAutoRulePage, type AutoRuleItem } from '@/api/finance'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { ElAlert, ElTag } from 'element-plus'
  import dayjs from 'dayjs'

  defineOptions({ name: 'OrderAutoRule' })

  const searchForm = ref<{ userId?: string; ruleStatus?: number }>({})
  const statusOptions = ref<{ label: string; value: number }[]>([])

  const searchItems = computed(() => [
    { label: '用户ID', key: 'userId', type: 'input', placeholder: '用户ID', clearable: true },
    {
      label: '规则状态',
      key: 'ruleStatus',
      type: 'select',
      placeholder: '规则状态',
      clearable: true,
      options: statusOptions.value
    }
  ])

  onMounted(async () => {
    statusOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.自动补货规则状态))
  })

  const dictLabel = (value: number) =>
    statusOptions.value.find((o) => o.value === value)?.label ?? String(value)

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
      apiFn: fetchAutoRulePage,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'id', label: '规则ID', width: 90 },
        { prop: 'userId', label: '用户ID', width: 100 },
        { prop: 'containerSpec', label: '规格', width: 90 },
        { prop: 'deliveryCount', label: '每期数量', width: 90 },
        { prop: 'intervalDays', label: '周期(天)', width: 90 },
        {
          prop: 'ruleStatus',
          label: '状态',
          width: 100,
          formatter: (row: AutoRuleItem) =>
            h(
              ElTag,
              {
                type: row.ruleStatus === 1 ? 'success' : row.ruleStatus === 2 ? 'warning' : 'info'
              },
              () => dictLabel(row.ruleStatus)
            )
        },
        {
          prop: 'anchorTime',
          label: '周期锚点',
          width: 170,
          formatter: (row: AutoRuleItem) => formatTime(row.anchorTime)
        },
        { prop: 'receiveAddress', label: '收货地址', minWidth: 200 },
        {
          prop: 'createTime',
          label: '创建时间',
          width: 170,
          formatter: (row: AutoRuleItem) => formatTime(row.createTime)
        }
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
