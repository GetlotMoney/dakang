<!-- 用户管理页面 -->
<!-- art-full-height 自动计算出页面剩余高度 -->
<!-- art-table-card 一个符合系统样式的 class，同时自动撑满剩余高度 -->
<!-- 更多 useTable 使用示例请移步至 功能示例 下面的高级表格示例或者查看官方文档 -->
<!-- useTable 文档：https://www.artd.pro/docs/zh/guide/hooks/use-table.html -->
<template>
  <div class="user-page">
    <!-- 左侧部门侧边栏 -->
    <DeptSidebar ref="deptSidebarRef" @select="handleDeptSelect" @refresh="refreshData" />

    <!-- 右侧主内容区 -->
    <div class="main-content">
      <!-- 搜索栏 -->
      <UserSearch v-model="searchForm" @search="handleSearch" @reset="handleReset"></UserSearch>

      <ElCard class="art-table-card">
        <!-- 表格头部 -->
        <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
          <template #left>
            <ElSpace wrap>
              <ElButton v-if="hasPermission('api:employee:add')" @click="showDialog('add')" v-ripple
                >新增用户</ElButton
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

        <!-- 用户弹窗 -->
        <UserDialog
          v-model:visible="dialogVisible"
          :type="dialogType"
          :user-data="currentUserData"
          :dept-tree="deptSidebarRef?.deptTree || []"
          :gender-options="genderOptions"
          :position-options="positionOptions"
          :tag-options="tagOptions"
          @submit="handleDialogSubmit"
          @refresh-position="handleRefreshPosition"
          @refresh-tag="handleRefreshTag"
          @open-position-dialog="handleOpenPositionDialog"
          @open-tag-dialog="handleOpenTagDialog"
        />

        <!-- 新增/编辑职务弹窗 -->
        <PositionDialog
          v-model:visible="positionDialogVisible"
          :type="positionDialogType"
          :position-data="currentPositionData"
          @submit="handlePositionSubmit"
        />

        <!-- 新增/编辑标签弹窗 -->
        <TagDialog
          v-model:visible="tagDialogVisible"
          :type="tagDialogType"
          :tag-data="currentTagData"
          @submit="handleTagSubmit"
        />

        <!-- 分配角色弹窗 -->
        <UserAssignRoleDialog
          v-model:visible="assignRoleDialogVisible"
          :user-data="currentAssignRoleUserData"
          @success="refreshData"
        />
      </ElCard>
    </div>
  </div>
</template>

<script setup lang="ts">
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchGetUserList, fetchDeleteUser, fetchGetPositionList } from '@/api/system-manage'
  import { fetchTagList } from '@/api/tag'
  import { fetchDictByTypes } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import UserSearch from './modules/user-search.vue'
  import UserDialog from './modules/user-dialog.vue'
  import UserAssignRoleDialog from './modules/user-assign-role-dialog.vue'
  import DeptSidebar from './modules/dept-sidebar.vue'
  import PositionDialog from '@/views/system/position/modules/position-dialog.vue'
  import TagDialog from '@/views/system/tag/modules/tag-dialog.vue'
  import { ElTag, ElMessageBox, ElMessage, ElButton, ElSpace } from 'element-plus'
  import { DialogType } from '@/types'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'User' })

  // 权限判断函数
  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  type UserListItem = Api.SystemManage.UserListItem

  // 弹窗相关
  const dialogType = ref<DialogType>('add')
  const dialogVisible = ref(false)
  const currentUserData = ref<Partial<UserListItem>>({})

  // 分配角色弹窗
  const assignRoleDialogVisible = ref(false)
  const currentAssignRoleUserData = ref<Partial<UserListItem>>({})

  // 职务弹窗
  const positionDialogVisible = ref(false)
  const positionDialogType = ref<DialogType>('add')
  const currentPositionData = ref<Partial<Api.SystemManage.PositionListItem>>({})

  // 标签弹窗
  const tagDialogVisible = ref(false)
  const tagDialogType = ref<DialogType>('add')
  const currentTagData = ref<Partial<Api.SystemManage.TagListItem>>({})

  // 选中行
  const selectedRows = ref<UserListItem[]>([])

  // 部门侧边栏引用
  const deptSidebarRef = ref()

  // 当前选中的部门ID
  const selectedDeptId = ref<string>()

  // 部门数据（用于表格显示）
  const deptList = ref<{ id: string; deptName: string }[]>([])

  // 部门选择处理
  const handleDeptSelect = (deptId: string | undefined) => {
    selectedDeptId.value = deptId
    replaceSearchParams({ deptId } as any)
    getData()
  }

  // 重置搜索参数
  const handleReset = () => {
    resetSearchParams()
    selectedDeptId.value = undefined
    deptSidebarRef.value?.clearSelection()
    getData()
  }

  // 性别选项（字典 20）
  const genderOptions = ref<{ label: string; value: number }[]>([])

  // 职务选项
  const positionOptions = ref<{ id: string; positionName: string }[]>([])

  // 标签选项
  const tagOptions = ref<{ id: string; tagName: string }[]>([])

  const getDeptName = (deptId: number | string) => {
    if (!deptId) return '-'
    return deptList.value.find((d) => String(d.id) === String(deptId))?.deptName || '-'
  }

  const getPositionName = (positionId: string | Number) => {
    if (!positionId) return '-'
    return positionOptions.value.find((p) => p.id === positionId)?.positionName || '-'
  }

  const getTagNames = (tagList: { id: string; tagName: string }[]) => {
    if (!tagList?.length) return h('span', '-')
    return h('div', { class: 'flex flex-wrap gap-1' }, [
      ...tagList.map((t) => h(ElTag, { type: 'info', size: 'small' }, () => t.tagName))
    ])
  }

  const loadDeptList = async () => {
    // 从部门侧边栏获取扁平化的部门列表用于表格显示
    const flatList = deptSidebarRef.value?.getFlatDeptList() || []
    deptList.value = flatList

    const [dictResult, positionRes, tagRes] = await Promise.all([
      fetchDictByTypes([DictTypeEnum.性别]),
      fetchGetPositionList({ current: 1, size: 9999 }),
      fetchTagList({})
    ])

    // 性别字典

    // 性别字典
    const genderDict = dictResult.find((d) => d.dictType === DictTypeEnum.性别)
    if (genderDict) {
      genderOptions.value = genderDict.dictDataList.map((item) => ({
        label: item.dictLabel,
        value: item.dictValue
      }))
    }

    // 职务列表
    if (positionRes.list) {
      positionOptions.value = positionRes.list
    }

    // 标签列表
    tagOptions.value = tagRes || []
  }

  // 搜索表单
  const searchForm = ref({
    employeeName: undefined,
    deptId: undefined
  })

  /**
   * 获取性别标签配置
   */
  const getGenderTag = (gender: number) => {
    const option = genderOptions.value.find((o) => o.value === gender)
    const text = option?.label || (gender === 1 ? '男' : '女')
    const type = gender === 1 ? ('primary' as const) : ('danger' as const)
    return h(ElTag, { type }, () => text)
  }

  /**
   * 获取状态标签配置
   */
  const getStatusTag = (disabledFlag: number) => {
    const config =
      disabledFlag === 1
        ? { type: 'success' as const, text: '启用' }
        : { type: 'danger' as const, text: '禁用' }
    return h(ElTag, { type: config.type }, () => config.text)
  }

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
      apiFn: fetchGetUserList,
      apiParams: {
        current: 1,
        size: 20,
        ...searchForm.value
      },
      columnsFactory: () => [
        {
          prop: 'loginName',
          label: '登录账号',
          minWidth: 120
        },
        {
          prop: 'employeeName',
          label: '用户姓名',
          minWidth: 120
        },
        {
          prop: 'employeeGender',
          label: '性别',
          minWidth: 80,
          formatter: (row: UserListItem) => getGenderTag(row.employeeGender)
        },
        {
          prop: 'employeePhone',
          label: '手机号',
          minWidth: 130
        },
        {
          prop: 'deptName',
          label: '部门',
          minWidth: 150,
          formatter: (row: UserListItem) => getDeptName(row.deptId)
        },
        {
          prop: 'positionName',
          label: '职务',
          minWidth: 120,
          formatter: (row: UserListItem) => getPositionName(row.positionId)
        },
        {
          prop: 'tagNameList',
          label: '标签',
          minWidth: 150,
          formatter: (row: UserListItem) => getTagNames(row.tagList)
        },
        {
          prop: 'disabledFlag',
          label: '状态',
          minWidth: 80,
          formatter: (row: UserListItem) => getStatusTag(row.disabledFlag)
        },
        {
          prop: 'operation',
          label: '操作',
          minWidth: 200,
          fixed: 'right',
          formatter: (row: UserListItem) =>
            h(ElSpace, { size: 8 }, () => [
              hasPermission('api:employee:assignRole') &&
                h(
                  ElButton,
                  {
                    size: 'small',
                    type: 'primary',
                    link: true,
                    onClick: () => showAssignRoleDialog(row)
                  },
                  () => '分配角色'
                ),
              hasPermission('api:employee:update') &&
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
              hasPermission('api:employee:delete') &&
                h(
                  ElButton,
                  {
                    size: 'small',
                    type: 'danger',
                    link: true,
                    onClick: () => deleteUser(row)
                  },
                  () => '删除'
                )
            ])
        }
      ]
    }
  })

  /**
   * 搜索处理
   */
  const handleSearch = (params: Api.SystemManage.UserSearchParams) => {
    replaceSearchParams(params)
    getData()
  }

  /**
   * 显示用户弹窗
   */
  const showDialog = (type: DialogType, row?: UserListItem): void => {
    dialogType.value = type
    currentUserData.value = row || {}
    nextTick(() => {
      dialogVisible.value = true
    })
  }

  /**
   * 显示分配角色弹窗
   */
  const showAssignRoleDialog = (row: UserListItem): void => {
    currentAssignRoleUserData.value = row
    assignRoleDialogVisible.value = true
  }

  /**
   * 删除用户
   */
  const deleteUser = (row: UserListItem): void => {
    ElMessageBox.confirm('确定要删除该用户吗？', '删除用户', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(async () => {
      try {
        await fetchDeleteUser(row.id)
        ElMessage.success('删除成功')
        refreshData()
      } catch {
        ElMessage.error('删除失败')
      }
    })
  }

  /**
   * 处理弹窗提交事件
   */
  const handleDialogSubmit = async () => {
    dialogVisible.value = false
    currentUserData.value = {}
    await loadDeptList()
    refreshData()
  }

  /**
   * 打开新增/编辑职务弹窗
   */
  const handleOpenPositionDialog = (data?: Partial<Api.SystemManage.PositionListItem>) => {
    positionDialogType.value = data?.id ? 'edit' : 'add'
    currentPositionData.value = data || {}
    positionDialogVisible.value = true
  }

  /**
   * 职务弹窗提交后刷新职务列表
   */
  const handlePositionSubmit = async () => {
    positionDialogVisible.value = false
    currentPositionData.value = {}
    await loadPositionOptions()
  }

  /**
   * 删除职务后刷新职务列表（不关闭用户弹窗）
   */
  const handleRefreshPosition = async () => {
    await loadPositionOptions()
  }

  /**
   * 删除标签后刷新标签列表（不关闭用户弹窗）
   */
  const handleRefreshTag = async () => {
    await loadTagOptions()
  }

  /**
   * 打开新增/编辑标签弹窗
   */
  const handleOpenTagDialog = (data?: Partial<Api.SystemManage.TagListItem>) => {
    tagDialogType.value = data?.id ? 'edit' : 'add'
    currentTagData.value = data || {}
    tagDialogVisible.value = true
  }

  /**
   * 标签弹窗提交后刷新标签列表
   */
  const handleTagSubmit = async () => {
    tagDialogVisible.value = false
    currentTagData.value = {}
    await loadTagOptions()
  }

  /**
   * 加载职务选项
   */
  const loadPositionOptions = async () => {
    const positionRes = await fetchGetPositionList({ current: 1, size: 9999 })
    if (positionRes.list) {
      positionOptions.value = positionRes.list
    }
  }

  /**
   * 加载标签选项
   */
  const loadTagOptions = async () => {
    const tagRes = await fetchTagList({})
    tagOptions.value = tagRes || []
  }

  /**
   * 处理表格行选择变化
   */
  const handleSelectionChange = (selection: UserListItem[]): void => {
    selectedRows.value = selection
  }

  onMounted(() => {
    loadDeptList()
  })
</script>

<style scoped lang="scss">
  .user-page {
    display: flex;
    height: 100%;
  }

  .main-content {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .art-table-card {
    flex: 1;
    display: flex;
    flex-direction: column;

    :deep(.el-card__body) {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
  }
</style>
