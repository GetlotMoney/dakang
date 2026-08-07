<!-- 批量控制（E2E-05 包D REQ-062/063）：批次列表 + 两步确认发起（preview→confirm）；
     聚合数量由子指令终态回写，单设备失败绝不显示全批成功 -->
<template>
  <div class="batch-page art-full-height">
    <BusinessModuleNav module-key="device" />

    <ArtSearchBar
      ref="searchBarRef"
      v-model="searchForm"
      :items="searchItems"
      auto-search
      @reset="handleReset"
      @search="handleSearch"
    />

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElButton v-if="canExecute" type="primary" @click="executeVisible = true">
            发起批量控制
          </ElButton>
        </template>
      </ArtTableHeader>

      <ArtTable
        row-key="id"
        :loading="loading"
        :columns="columns"
        :data="data"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      />

      <BatchDetailDrawer v-model="drawerVisible" :batch-id="currentId" />
      <BatchExecuteDialog v-model="executeVisible" @executed="refreshData" />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { h } from 'vue'
  import { ElTag } from 'element-plus'
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchBatchPage, type BatchItem } from '@/api/device'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import BatchDetailDrawer from './modules/batch-detail-drawer.vue'
  import BatchExecuteDialog from './modules/batch-execute-dialog.vue'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'DeviceBatch' })

  const userStore = useUserStore()
  const canExecute = computed(() =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === 'device:batch:execute')
  )

  const searchBarRef = ref()
  const drawerVisible = ref(false)
  const executeVisible = ref(false)
  const currentId = ref<number>()

  const searchForm = ref({
    batchNo: undefined as string | undefined,
    batchStatus: undefined as number | undefined
  })

  const cmdTypeOptions = ref<{ label: string; value: number }[]>([])
  const batchStatusOptions = ref<{ label: string; value: number }[]>([])
  const scopeOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [cmdTypes, statuses, scopes] = await Promise.all([
      fetchDictOptions(DictTypeEnum.指令类型),
      fetchDictOptions(DictTypeEnum.批次聚合状态),
      fetchDictOptions(DictTypeEnum.批量范围)
    ])
    cmdTypeOptions.value = toDictOptions(cmdTypes)
    batchStatusOptions.value = toDictOptions(statuses)
    scopeOptions.value = toDictOptions(scopes)
    getData()
  })

  const cmdTypeLabel = (v: number) =>
    cmdTypeOptions.value.find((o) => o.value === v)?.label || String(v)
  const batchStatusLabel = (v: number) =>
    batchStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const scopeLabel = (v: number) =>
    scopeOptions.value.find((o) => o.value === v)?.label || String(v)
  // 1处理中 2全部成功 3部分成功 4全部失败
  const batchStatusTagType = (v: number) =>
    v === 2 ? 'success' : v === 3 ? 'warning' : v === 4 ? 'danger' : 'primary'

  const searchItems = computed(() => [
    {
      label: '批次号',
      key: 'batchNo',
      type: 'input',
      placeholder: '请输入批次号',
      clearable: true
    },
    {
      label: '聚合状态',
      key: 'batchStatus',
      type: 'select',
      placeholder: '请选择状态',
      clearable: true,
      options: batchStatusOptions.value
    }
  ])

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
      apiFn: fetchBatchPage,
      apiParams: {
        current: 1,
        size: 20,
        ...searchForm.value
      },
      immediate: false,
      columnsFactory: () => [
        { prop: 'batchNo', label: '批次号', minWidth: 190 },
        {
          prop: 'cmdType',
          label: '指令类型',
          width: 110,
          formatter: (row: BatchItem) => cmdTypeLabel(row.cmdType)
        },
        {
          prop: 'scopeType',
          label: '范围',
          width: 100,
          formatter: (row: BatchItem) => scopeLabel(row.scopeType)
        },
        {
          prop: 'totalCount',
          label: '总数',
          width: 70
        },
        {
          prop: 'progress',
          label: '成功/失败/超时',
          minWidth: 130,
          formatter: (row: BatchItem) =>
            `${row.successCount} / ${row.failCount} / ${row.timeoutCount}`
        },
        {
          prop: 'batchStatus',
          label: '聚合状态',
          width: 100,
          formatter: (row: BatchItem) =>
            h(ElTag, { type: batchStatusTagType(row.batchStatus) as any, size: 'small' }, () =>
              batchStatusLabel(row.batchStatus)
            )
        },
        {
          prop: 'createTime',
          label: '发起时间',
          minWidth: 150,
          formatter: (row: BatchItem) => formatTime(row.createTime)
        },
        {
          prop: 'finishTime',
          label: '完成时间',
          minWidth: 150,
          formatter: (row: BatchItem) => formatTime(row.finishTime)
        },
        {
          prop: 'operation',
          label: '操作',
          width: 90,
          fixed: 'right',
          formatter: (row: BatchItem) =>
            h(ArtButtonTable, {
              type: 'view',
              onClick: () => {
                currentId.value = row.id
                drawerVisible.value = true
              }
            })
        }
      ]
    }
  })

  function formatTime(time?: string) {
    if (!time || time.length !== 14) return time || '-'
    return `${time.slice(0, 4)}-${time.slice(4, 6)}-${time.slice(6, 8)} ${time.slice(8, 10)}:${time.slice(10, 12)}`
  }

  function handleSearch() {
    replaceSearchParams({ current: 1, size: pagination.size, ...searchForm.value })
  }

  function handleReset() {
    searchForm.value = { batchNo: undefined, batchStatus: undefined }
    resetSearchParams()
  }
</script>

<style scoped lang="scss">
  .batch-page {
    display: flex;
    flex-direction: column;
  }
</style>
