<!-- 运维工单（E2E-05 包D REQ-038/039/040）：告警转入/机主申报/后台创建三来源统一列表；六状态动作全部在详情抽屉里按当前状态收敛 -->
<template>
  <div class="workorder-page art-full-height">
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
          <ElButton v-if="canHandle" type="primary" @click="createVisible = true">
            后台建单
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

      <WorkOrderDetailDrawer
        v-model="drawerVisible"
        :work-order-id="currentId"
        :can-handle="canHandle"
        @changed="refreshData"
      />

      <WorkOrderCreateDialog v-model="createVisible" @created="refreshData" />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { h } from 'vue'
  import { ElTag } from 'element-plus'
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchWorkOrderPage, type WorkOrderItem } from '@/api/device'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import WorkOrderDetailDrawer from './modules/workorder-detail-drawer.vue'
  import WorkOrderCreateDialog from './modules/workorder-create-dialog.vue'
  import { useUserStore } from '@/store/modules/user'
  import { WORK_ORDER_SOURCE_LABELS, workOrderStatusTagType } from './modules/workorder-meta'

  defineOptions({ name: 'DeviceWorkorder' })

  const route = useRoute()
  const userStore = useUserStore()
  const canHandle = computed(() =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === 'device:workorder:handle')
  )

  const searchBarRef = ref()
  const drawerVisible = ref(false)
  const createVisible = ref(false)
  const currentId = ref<number>()

  const searchForm = ref({
    orderNo: (route.query.orderNo as string) || undefined,
    orderStatus: undefined as number | undefined,
    filterWorkType: undefined as number | undefined,
    sourceType: undefined as number | undefined
  })

  const statusOptions = ref<{ label: string; value: number }[]>([])
  const typeOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [statuses, types] = await Promise.all([
      fetchDictOptions(DictTypeEnum.工单状态),
      fetchDictOptions(DictTypeEnum.工单类型)
    ])
    statusOptions.value = toDictOptions(statuses)
    typeOptions.value = toDictOptions(types)
    getData()
  })

  const statusLabel = (v: number) =>
    statusOptions.value.find((o) => o.value === v)?.label || String(v)
  const typeLabel = (v: number) => typeOptions.value.find((o) => o.value === v)?.label || String(v)

  const searchItems = computed(() => [
    {
      label: '工单号',
      key: 'orderNo',
      type: 'input',
      placeholder: '请输入工单号',
      clearable: true
    },
    {
      label: '状态',
      key: 'orderStatus',
      type: 'select',
      placeholder: '请选择状态',
      clearable: true,
      options: statusOptions.value
    },
    {
      label: '类型',
      key: 'filterWorkType',
      type: 'select',
      placeholder: '请选择类型',
      clearable: true,
      options: typeOptions.value
    },
    {
      label: '来源',
      key: 'sourceType',
      type: 'select',
      placeholder: '请选择来源',
      clearable: true,
      options: WORK_ORDER_SOURCE_LABELS.map((label, index) => ({ label, value: index + 1 }))
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
      apiFn: fetchWorkOrderPage,
      apiParams: {
        current: 1,
        size: 20,
        ...searchForm.value
      },
      immediate: false,
      columnsFactory: () => [
        { prop: 'orderNo', label: '工单号', minWidth: 190 },
        {
          prop: 'workType',
          label: '类型',
          width: 80,
          formatter: (row: WorkOrderItem) => typeLabel(row.workType)
        },
        {
          prop: 'sourceType',
          label: '来源',
          width: 100,
          formatter: (row: WorkOrderItem) => WORK_ORDER_SOURCE_LABELS[row.sourceType - 1] || '-'
        },
        { prop: 'orderTitle', label: '标题', minWidth: 200, showOverflowTooltip: true },
        {
          prop: 'deviceNo',
          label: '设备',
          minWidth: 130,
          formatter: (row: WorkOrderItem) => row.deviceNo || '-'
        },
        {
          prop: 'stationName',
          label: '水站',
          minWidth: 120,
          formatter: (row: WorkOrderItem) => row.stationName || '-'
        },
        {
          prop: 'assigneeName',
          label: '处理人',
          width: 100,
          formatter: (row: WorkOrderItem) => row.assigneeName || '-'
        },
        {
          prop: 'orderStatus',
          label: '状态',
          width: 100,
          formatter: (row: WorkOrderItem) =>
            h(ElTag, { type: workOrderStatusTagType(row.orderStatus) as any, size: 'small' }, () =>
              statusLabel(row.orderStatus)
            )
        },
        {
          prop: 'createTime',
          label: '创建时间',
          minWidth: 150,
          formatter: (row: WorkOrderItem) => formatTime(row.createTime)
        },
        {
          prop: 'operation',
          label: '操作',
          width: 90,
          fixed: 'right',
          formatter: (row: WorkOrderItem) =>
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
    searchForm.value = {
      orderNo: undefined,
      orderStatus: undefined,
      filterWorkType: undefined,
      sourceType: undefined
    }
    resetSearchParams()
  }
</script>

<style scoped lang="scss">
  .workorder-page {
    display: flex;
    flex-direction: column;
  }
</style>
