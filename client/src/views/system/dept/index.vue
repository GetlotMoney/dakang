<template>
  <div class="dept-page art-full-height">
    <ElCard class="art-table-card">
      <!-- 表格头部 -->
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="getDeptTree">
        <template #left>
          <ElButton v-if="hasPermission('api:dept:add')" @click="showDialog('add')" v-ripple
            >新增部门</ElButton
          >
          <ElButton @click="toggleExpand" v-ripple>
            {{ isExpanded ? '收起' : '展开' }}
          </ElButton>
        </template>
      </ArtTableHeader>

      <!-- 树形表格 -->
      <ArtTable
        ref="tableRef"
        row-key="id"
        :loading="loading"
        :columns="columns"
        :data="tableData"
        :stripe="false"
        :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
        :default-expand-all="false"
      >
      </ArtTable>

      <!-- 部门弹窗 -->
      <DeptDialog
        v-model:visible="dialogVisible"
        :type="dialogType"
        :dept-data="currentDeptData"
        :dept-tree="tableData"
        :user-options="userOptions"
        @submit="getDeptTree"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import { fetchGetDeptTree, fetchDeleteDept } from '@/api/dept'
  import { fetchGetUserList } from '@/api/system-manage'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import DeptDialog from './modules/dept-dialog.vue'
  import { DialogType } from '@/types'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'Dept' })

  // 权限判断函数
  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  type DeptListItem = Api.SystemManage.DeptListItem
  type UserListItem = Api.SystemManage.UserListItem

  // 状态管理
  const loading = ref(false)
  const isExpanded = ref(false)
  const tableRef = ref()

  // 用户列表（预加载，供弹窗使用）
  const userOptions = ref<UserListItem[]>([])

  // 获取用户列表（预加载）
  const getUserOptions = async (): Promise<void> => {
    try {
      const res = await fetchGetUserList({ current: 1, size: 9999 })
      userOptions.value = res?.list || []
    } catch {
      // 静默失败，不影响主流程
    }
  }

  // 弹窗相关
  const dialogType = ref<DialogType>('add')
  const dialogVisible = ref(false)
  const currentDeptData = ref<Partial<DeptListItem>>({})

  // 原始数据
  const tableData = ref<DeptListItem[]>([])

  // 切换展开/收起
  const toggleExpand = () => {
    isExpanded.value = !isExpanded.value
    const toggleRows = (rows: DeptListItem[], expand: boolean) => {
      rows.forEach((row) => {
        tableRef.value?.elTableRef?.toggleRowExpansion(row, expand)
        if (row.children?.length) {
          toggleRows(row.children, expand)
        }
      })
    }
    toggleRows(tableData.value, isExpanded.value)
  }

  // 获取部门树形数据
  const getDeptTree = async (): Promise<void> => {
    loading.value = true
    try {
      const data = await fetchGetDeptTree()
      tableData.value = data || []
    } catch (error) {
      throw error instanceof Error ? error : new Error('获取部门失败')
    } finally {
      loading.value = false
    }
  }

  // 表格列配置
  const { columnChecks, columns } = useTableColumns(() => [
    {
      prop: 'deptName',
      label: '部门名称',
      minWidth: 180
    },
    {
      prop: 'deptSort',
      label: '排序',
      width: 100
    },
    {
      prop: 'deptManagerIdToEmployee',
      label: '部门负责人',
      width: 120,
      formatter: (row: DeptListItem) => {
        if (!row.deptManagerIdToEmployee) return '-'
        return row.deptManagerIdToEmployee.employeeName || '-'
      }
    },
    {
      prop: 'deptDesc',
      label: '部门描述',
      minWidth: 200,
      showOverflowTooltip: true,
      formatter: (row: DeptListItem) => row.deptDesc || '-'
    },
    {
      prop: 'operation',
      label: '操作',
      width: 150,
      fixed: 'right',
      formatter: (row: DeptListItem) =>
        h('div', [
          hasPermission('api:dept:update') &&
            h(ArtButtonTable, {
              type: 'edit',
              onClick: () => showDialog('edit', row)
            }),
          hasPermission('api:dept:delete') &&
            h(ArtButtonTable, {
              type: 'delete',
              onClick: () => deleteDept(row)
            })
        ])
    }
  ])

  // 显示弹窗
  const showDialog = (type: DialogType, row?: DeptListItem): void => {
    dialogType.value = type
    currentDeptData.value = row || {}
    nextTick(() => {
      dialogVisible.value = true
    })
  }

  // 删除部门
  const deleteDept = (row: DeptListItem): void => {
    ElMessageBox.confirm('确定要删除该部门吗？', '删除部门', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(async () => {
      try {
        await fetchDeleteDept(row.id)
        ElMessage.success('删除成功')
        getDeptTree()
      } catch {
        ElMessage.error('删除失败')
      }
    })
  }

  onMounted(() => {
    getDeptTree()
    getUserOptions()
  })
</script>
