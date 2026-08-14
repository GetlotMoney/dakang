<!-- 登录日志页面 -->
<template>
  <div class="art-full-height">
    <LoginLogSearch
      v-show="showSearchBar"
      v-model="searchForm"
      :log-type-options="logTypeOptions"
      @search="handleSearch"
      @reset="resetSearchParams"
    ></LoginLogSearch>

    <ElCard class="art-table-card" :style="{ 'margin-top': showSearchBar ? '12px' : '0' }">
      <ArtTableHeader
        v-model:columns="columnChecks"
        v-model:showSearchBar="showSearchBar"
        :loading="loading"
        @refresh="refreshData"
      >
        <template #left>
          <BusinessTableSummary :total="pagination.total" :page-size="data.length" />
        </template>
        <template #right>
          <ElButton v-if="canAuditExport" type="primary" plain @click="openAuditExport">
            申请导出
          </ElButton>
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

    <!-- 登录日志详情抽屉 -->
    <LoginLogDetailDrawer
      v-model="detailVisible"
      :log-id="currentLogId"
      :log-type-options="logTypeOptions"
    />
  </div>
</template>

<script setup lang="ts">
  import { useTable } from '@/hooks/core/useTable'
  import { fetchGetLogLoginList } from '@/api/log'
  import { fetchDictByTypes } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import LoginLogSearch from './modules/login-log-search.vue'
  import LoginLogDetailDrawer from './modules/login-log-detail.vue'
  import BusinessTableSummary from '@/components/business/business-table-summary/index.vue'
  import { useUserStore } from '@/store/modules/user'
  import { ElSpace } from 'element-plus'
  import dayjs from 'dayjs'

  defineOptions({ name: 'LoginLog' })

  const router = useRouter()
  const userStore = useUserStore()
  const canAuditExport = computed(() =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === 'system:audit:export')
  )

  type LogLoginItem = Api.Log.LogLoginItem
  type LogLoginSearchParams = Api.Log.LogLoginSearchParams & {
    daterange?: string[]
  }

  // 搜索表单
  const searchForm = ref<LogLoginSearchParams>({
    logUserName: undefined,
    logType: undefined,
    daterange: undefined
  })

  const showSearchBar = ref(false)

  const detailVisible = ref(false)
  const currentLogId = ref<string>('')

  const logTypeOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const dictData = await fetchDictByTypes([DictTypeEnum.登录类型])
    logTypeOptions.value = dictData[0].dictDataList.map((d) => ({
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
      apiFn: fetchGetLogLoginList,
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
          prop: 'logType',
          label: '登录类型',
          width: 100,
          formatter: (row: LogLoginItem) => {
            const option = logTypeOptions.value.find((o) => o.value === row.logType)
            return option?.label ?? row.logType
          }
        },
        {
          prop: 'logExecuteTime',
          label: '执行时间',
          minWidth: 180,
          formatter: (row: LogLoginItem) =>
            dayjs(row.logExecuteTime, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss')
        },
        {
          prop: 'logIp',
          label: '登录IP',
          minWidth: 140
        },
        {
          prop: 'operation',
          label: '操作',
          width: 100,
          fixed: 'right',
          formatter: (row: LogLoginItem) =>
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

  const handleSearch = (params: LogLoginSearchParams) => {
    const { daterange, ...filterParams } = params
    const [logExecuteTimeBegin, logExecuteTimeEnd] = Array.isArray(daterange)
      ? daterange
      : [null, null]
    replaceSearchParams({ ...filterParams, logExecuteTimeBegin, logExecuteTimeEnd })
    getData()
  }

  const showDetail = (row: LogLoginItem) => {
    currentLogId.value = row.id
    detailVisible.value = true
  }

  const openAuditExport = () => {
    router.push({ path: '/system/compliance', query: { applyScope: '登录日志' } })
  }
</script>
