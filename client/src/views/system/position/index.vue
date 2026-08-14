<!-- 职务管理页面 -->
<template>
  <div class="position-page art-full-height">
    <!-- 搜索栏 -->
    <PositionSearch
      v-model="searchForm"
      @search="handleSearch"
      @reset="resetSearchParams"
    ></PositionSearch>

    <ElCard class="art-table-card">
      <!-- 表格头部 -->
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElSpace wrap>
            <ElButton v-if="hasPermission('api:position:add')" @click="showDialog('add')" v-ripple
              >新增职务</ElButton
            >
          </ElSpace>
        </template>
      </ArtTableHeader>

      <!-- 表格 -->
      <ArtTable
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @selection-change="handleSelectionChange"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
      </ArtTable>

      <!-- 职务弹窗 -->
      <PositionDialog
        v-model:visible="dialogVisible"
        :type="dialogType"
        :position-data="currentPositionData"
        @submit="handleDialogSubmit"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchGetPositionList, fetchDeletePosition } from '@/api/system-manage'
  import PositionSearch from './modules/position-search.vue'
  import PositionDialog from './modules/position-dialog.vue'
  import { ElMessageBox, ElMessage } from 'element-plus'
  import { DialogType } from '@/types'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'Position' })

  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  type PositionListItem = Api.SystemManage.PositionListItem

  const dialogType = ref<DialogType>('add')
  const dialogVisible = ref(false)
  const currentPositionData = ref<Partial<PositionListItem>>({})

  const selectedRows = ref<PositionListItem[]>([])

  const searchForm = ref({
    positionName: undefined,
    positionLevel: undefined
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
      apiFn: fetchGetPositionList,
      apiParams: {
        current: 1,
        size: 20,
        ...searchForm.value
      },
      columnsFactory: () => [
        {
          prop: 'positionName',
          label: '职务名称',
          width: 150
        },
        {
          prop: 'positionLevel',
          label: '职务等级',
          width: 100
        },
        {
          prop: 'positionSort',
          label: '排序',
          width: 80
        },
        {
          prop: 'positionRemark',
          label: '备注',
          minWidth: 200,
          showOverflowTooltip: true,
          formatter: (row: PositionListItem) => row.positionRemark || '-'
        },
        {
          prop: 'operation',
          label: '操作',
          width: 120,
          fixed: 'right',
          formatter: (row: PositionListItem) =>
            h('div', [
              hasPermission('api:position:update') &&
                h(ArtButtonTable, {
                  type: 'edit',
                  onClick: () => showDialog('edit', row)
                }),
              hasPermission('api:position:delete') &&
                h(ArtButtonTable, {
                  type: 'delete',
                  onClick: () => deletePosition(row)
                })
            ])
        }
      ]
    }
  })

  const handleSearch = (params: Api.SystemManage.PositionSearchParams) => {
    replaceSearchParams(params)
    getData()
  }

  const showDialog = (type: DialogType, row?: PositionListItem): void => {
    dialogType.value = type
    currentPositionData.value = row || {}
    nextTick(() => {
      dialogVisible.value = true
    })
  }

  const deletePosition = (row: PositionListItem): void => {
    ElMessageBox.confirm('确定要删除该职务吗？', '删除职务', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(async () => {
      try {
        await fetchDeletePosition(row.id)
        ElMessage.success('删除成功')
        refreshData()
      } catch {
        ElMessage.error('删除失败')
      }
    })
  }

  const handleDialogSubmit = async () => {
    dialogVisible.value = false
    currentPositionData.value = {}
    refreshData()
  }

  const handleSelectionChange = (selection: PositionListItem[]): void => {
    selectedRows.value = selection
  }
</script>
