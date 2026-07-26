<!-- 水站管理（一期 5+1 之一，REQ-019 / MVP 必需） -->
<template>
  <div class="station-page art-full-height">
    <!-- 搜索栏 -->
    <StationSearch v-model="searchForm" @search="handleSearch" @reset="handleReset"></StationSearch>

    <ElCard class="art-table-card">
      <!-- 表格头部 -->
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElButton v-if="hasPermission('station:station:add')" @click="showDialog('add')" v-ripple
            >新增水站</ElButton
          >
        </template>
      </ArtTableHeader>

      <!-- 表格 -->
      <ArtTable
        row-key="id"
        :loading="loading"
        :columns="columns"
        :data="data"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
      </ArtTable>

      <!-- 新增/编辑弹窗 -->
      <StationDialog
        v-model:visible="dialogVisible"
        :type="dialogType"
        :station-data="currentRow"
        @submit="refreshData"
      />

      <!-- 详情抽屉 -->
      <StationDetailDrawer v-model:visible="drawerVisible" :station-id="currentRow.id" />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { h } from 'vue'
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchStationPage, fetchDeleteStation, type StationItem } from '@/api/station'
  import { ElMessage, ElMessageBox, ElTag } from 'element-plus'
  import StationSearch from './modules/station-search.vue'
  import StationDialog from './modules/station-dialog.vue'
  import StationDetailDrawer from './modules/station-detail-drawer.vue'
  import { DialogType } from '@/types'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'Station' })

  // 权限判断函数
  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  // 弹窗/抽屉
  const dialogType = ref<DialogType>('add')
  const dialogVisible = ref(false)
  const drawerVisible = ref(false)
  const currentRow = ref<Partial<StationItem>>({})

  // 搜索表单
  const searchForm = ref({
    stationName: undefined as string | undefined,
    stationCode: undefined as string | undefined,
    stationRegion: undefined as string | undefined,
    stationStatus: undefined as number | undefined
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
      apiFn: fetchStationPage,
      apiParams: {
        current: 1,
        size: 20,
        ...searchForm.value
      },
      columnsFactory: () => [
        { prop: 'stationName', label: '水站名称', minWidth: 140 },
        { prop: 'stationCode', label: '水站编码', minWidth: 120 },
        { prop: 'stationRegion', label: '所属区域', minWidth: 130 },
        { prop: 'stationAddress', label: '详细地址', minWidth: 200, showOverflowTooltip: true },
        {
          prop: 'ownerUserName',
          label: '机主',
          minWidth: 110,
          formatter: (row: StationItem) => row.ownerUserName || '未绑定'
        },
        {
          prop: 'deviceCount',
          label: '设备数',
          minWidth: 80,
          formatter: (row: StationItem) => `${row.deviceCount ?? 0} 台`
        },
        {
          prop: 'stationStatus',
          label: '状态',
          minWidth: 80,
          formatter: (row: StationItem) =>
            h(ElTag, { type: row.stationStatus === 1 ? 'success' : 'danger' }, () =>
              row.stationStatus === 1 ? '正常' : '禁用'
            )
        },
        {
          prop: 'operation',
          label: '操作',
          width: 200,
          fixed: 'right',
          formatter: (row: StationItem) =>
            h('div', [
              h(ArtButtonTable, {
                type: 'view',
                onClick: () => showDrawer(row)
              }),
              hasPermission('station:station:update') &&
                h(ArtButtonTable, {
                  type: 'edit',
                  onClick: () => showDialog('edit', row)
                }),
              hasPermission('station:station:delete') &&
                h(ArtButtonTable, {
                  type: 'delete',
                  onClick: () => deleteStation(row)
                })
            ])
        }
      ]
    }
  })

  // 搜索
  const handleSearch = (params: Record<string, any>) => {
    replaceSearchParams(params)
    void getData()
  }

  // 重置
  const handleReset = () => {
    resetSearchParams()
  }

  // 弹窗
  const showDialog = (type: DialogType, row?: StationItem): void => {
    dialogType.value = type
    currentRow.value = row || {}
    nextTick(() => {
      dialogVisible.value = true
    })
  }

  // 详情抽屉
  const showDrawer = (row: StationItem): void => {
    currentRow.value = row
    nextTick(() => {
      drawerVisible.value = true
    })
  }

  // 删除
  const deleteStation = (row: StationItem): void => {
    ElMessageBox.confirm(
      `确定要删除水站「${row.stationName}」吗？站下存在设备时将无法删除。`,
      '删除水站',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    ).then(async () => {
      await fetchDeleteStation(row.id)
      ElMessage.success('删除成功')
      getData()
    })
  }
</script>
