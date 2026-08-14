<!-- 角色管理页面 -->
<template>
  <div class="art-full-height">
    <RoleSearch
      v-show="showSearchBar"
      v-model="searchForm"
      @search="handleSearch"
      @reset="resetSearchParams"
    ></RoleSearch>

    <ElCard class="art-table-card" :style="{ 'margin-top': showSearchBar ? '12px' : '0' }">
      <ArtTableHeader
        v-model:columns="columnChecks"
        v-model:showSearchBar="showSearchBar"
        :loading="loading"
        @refresh="refreshData"
      >
        <template #left>
          <ElSpace wrap>
            <ElButton v-if="hasPermission('api:role:add')" @click="showDialog('add')" v-ripple
              >新增角色</ElButton
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
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
      </ArtTable>
    </ElCard>

    <!-- 角色编辑弹窗 -->
    <RoleEditDialog
      v-model="dialogVisible"
      :dialog-type="dialogType"
      :role-data="currentRoleData"
      @success="refreshData"
    />

    <!-- 角色授权弹窗（菜单权限 / 操作权限分栏） -->
    <RolePermissionDialog
      v-model="permissionDialog"
      :role-data="currentRoleData"
      @success="refreshData"
    />
  </div>
</template>

<script setup lang="ts">
  import { useTable } from '@/hooks/core/useTable'
  import { fetchGetRoleList, fetchDeleteRole } from '@/api/system-manage'
  import RoleSearch from './modules/role-search.vue'
  import RoleEditDialog from './modules/role-edit-dialog.vue'
  import RolePermissionDialog from './modules/role-permission-dialog.vue'
  import { ElMessageBox, ElButton, ElSpace } from 'element-plus'
  import dayjs from 'dayjs'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'Role' })

  // 权限判断函数
  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  type RoleListItem = Api.SystemManage.RoleListItem
  // 创建日期筛选已撤：控件提交 startTime/endTime，而角色查询根本不过滤这两个参数——
  // 选了不生效也不报错的筛选比没有筛选更误事
  type RoleSearchFormParams = Api.SystemManage.RoleSearchParams

  // 搜索表单
  const searchForm = ref<RoleSearchFormParams>({
    roleName: undefined,
    roleCode: undefined
  })

  const showSearchBar = ref(false)

  const dialogVisible = ref(false)
  const permissionDialog = ref(false)
  const currentRoleData = ref<RoleListItem | undefined>(undefined)

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
    // 核心配置
    core: {
      apiFn: fetchGetRoleList,
      apiParams: {
        current: 1,
        size: 20
      },
      columnsFactory: () => [
        {
          prop: 'id',
          label: '角色ID',
          width: 100
        },
        {
          prop: 'roleName',
          label: '角色名称',
          minWidth: 120
        },
        {
          prop: 'roleCode',
          label: '角色编码',
          minWidth: 120
        },
        {
          prop: 'roleRemark',
          label: '角色备注',
          minWidth: 150,
          showOverflowTooltip: true
        },
        {
          prop: 'roleSort',
          label: '排序',
          width: 80
        },
        {
          prop: 'createTime',
          label: '创建日期',
          width: 180,
          sortable: true,
          formatter: (row: RoleListItem) =>
            dayjs(row.createTime, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss')
        },
        {
          prop: 'updateTime',
          label: '更新日期',
          width: 180,
          sortable: true,
          formatter: (row: RoleListItem) =>
            dayjs(row.updateTime, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss')
        },
        {
          prop: 'operation',
          label: '操作',
          width: 200,
          fixed: 'right',
          formatter: (row) =>
            h(ElSpace, { size: 8 }, () => [
              hasPermission('api:role:update') &&
                h(
                  ElButton,
                  {
                    size: 'small',
                    type: 'primary',
                    link: true,
                    onClick: () => showDialog('edit', row)
                  },
                  () => '编辑'
                ),
              hasPermission('api:role:permission') &&
                h(
                  ElButton,
                  {
                    size: 'small',
                    type: 'primary',
                    link: true,
                    onClick: () => showPermissionDialog(row)
                  },
                  () => '分配权限'
                ),
              hasPermission('api:role:delete') &&
                h(
                  ElButton,
                  { size: 'small', type: 'danger', link: true, onClick: () => deleteRole(row) },
                  () => '删除'
                )
            ])
        }
      ]
    }
  })

  const dialogType = ref<'add' | 'edit'>('add')

  const showDialog = (type: 'add' | 'edit', row?: RoleListItem) => {
    dialogVisible.value = true
    dialogType.value = type
    currentRoleData.value = row
  }

  /**
   * 搜索处理
   * @param params 搜索参数
   */
  const handleSearch = (params: RoleSearchFormParams) => {
    replaceSearchParams({ ...params })
    getData()
  }

  const showPermissionDialog = (row?: RoleListItem) => {
    permissionDialog.value = true
    currentRoleData.value = row
  }

  const deleteRole = (row: RoleListItem) => {
    ElMessageBox.confirm(`确定删除角色"${row.roleName}"吗？此操作不可恢复！`, '删除确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
      .then(async () => {
        await fetchDeleteRole(row.id!)
        ElMessage.success('删除成功')
        refreshData()
      })
      .catch(() => {
        ElMessage.info('已取消删除')
      })
  }
</script>
