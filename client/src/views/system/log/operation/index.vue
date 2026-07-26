<!-- 操作日志页面 -->
<template>
  <div class="art-full-height">
    <OperationLogSearch
      v-show="showSearchBar"
      v-model="searchForm"
      :log-success-flag-options="logSuccessFlagOptions"
      @search="handleSearch"
      @reset="resetSearchParams"
    ></OperationLogSearch>

    <ElCard class="art-table-card" :style="{ 'margin-top': showSearchBar ? '12px' : '0' }">
      <ArtTableHeader
        v-model:columns="columnChecks"
        v-model:showSearchBar="showSearchBar"
        :loading="loading"
        @refresh="refreshData"
      >
        <template #left>
          <ElSpace wrap> </ElSpace>
        </template>
      </ArtTableHeader>

      <!-- 表格 -->
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

    <!-- 操作日志详情抽屉 -->
    <OperationLogDetailDrawer
      v-model="detailVisible"
      :log-id="currentLogId"
      :log-success-flag-options="logSuccessFlagOptions"
    />
  </div>
</template>

<script setup lang="ts">
  import { useTable } from '@/hooks/core/useTable'
  import { fetchGetLogOperationList } from '@/api/log'
  import { fetchDictByTypes } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import OperationLogSearch from './modules/operation-log-search.vue'
  import OperationLogDetailDrawer from './modules/operation-log-detail.vue'
  import { ElSpace } from 'element-plus'
  import dayjs from 'dayjs'

  defineOptions({ name: 'OperationLog' })

  type LogOperationItem = Api.Log.LogOperationItem
  type LogOperationSearchParams = Api.Log.LogOperationSearchParams & {
    daterange?: string[]
  }

  // 搜索表单
  const searchForm = ref<LogOperationSearchParams>({
    logUserName: undefined,
    logUserType: undefined,
    logModule: undefined,
    logSuccessFlag: undefined,
    daterange: undefined
  })

  const showSearchBar = ref(false)

  const detailVisible = ref(false)
  const currentLogId = ref<string>('')

  const logSuccessFlagOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const dictData = await fetchDictByTypes([DictTypeEnum.是否])
    logSuccessFlagOptions.value = dictData[0].dictDataList.map((d) => ({
      label: d.dictLabel,
      value: d.dictValue
    }))
  })

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    resetSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchGetLogOperationList,
      apiParams: {
        current: 1,
        size: 20
      },
      excludeParams: ['daterange'],
      columnsFactory: () => [
        {
          prop: 'id',
          label: 'ID',
          width: 80
        },
        {
          prop: 'logUserName',
          label: '用户名',
          minWidth: 120
        },
        {
          prop: 'logModule',
          label: '操作模块',
          minWidth: 140
        },
        {
          prop: 'logContent',
          label: '操作内容',
          minWidth: 160
        },
        {
          prop: 'logExecuteTime',
          label: '操作时间',
          minWidth: 180,
          formatter: (row: LogOperationItem) =>
            dayjs(row.logExecuteTime, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss')
        },
        {
          prop: 'logConsumerTime',
          label: '耗时(ms)',
          width: 100
        },
        {
          prop: 'logSuccessFlag',
          label: '执行结果',
          width: 100,
          formatter: (row: LogOperationItem) => {
            const option = logSuccessFlagOptions.value.find((o) => o.value === row.logSuccessFlag)
            return option?.label ?? row.logSuccessFlag
          }
        },
        {
          prop: 'logIp',
          label: '操作IP',
          minWidth: 140
        },
        {
          prop: 'operation',
          label: '操作',
          width: 100,
          fixed: 'right',
          formatter: (row: LogOperationItem) =>
            h(ElSpace, { size: 8 }, () => [
              h(
                ElButton,
                {
                  size: 'small',
                  type: 'primary',
                  link: true,
                  onClick: () => showDetail(row)
                },
                () => '详情'
              )
            ])
        }
      ]
    }
  })

  const handleSearch = (params: LogOperationSearchParams) => {
    const { daterange, ...filterParams } = params
    const [logExecuteTimeBegin, logExecuteTimeEnd] = Array.isArray(daterange)
      ? daterange
      : [null, null]
    replaceSearchParams({ ...filterParams, logExecuteTimeBegin, logExecuteTimeEnd })
    getData()
  }

  const showDetail = (row: LogOperationItem) => {
    currentLogId.value = row.id
    detailVisible.value = true
  }
</script>
