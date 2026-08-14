<!-- 用户管理页面 -->
<template>
  <div ref="userPageRef" class="user-page">
    <!-- 左侧部门侧边栏 -->
    <DeptSidebar
      v-if="canQueryDept"
      ref="deptSidebarRef"
      :compact="isCompactLayout"
      :can-add="hasPermission('api:dept:add')"
      :can-update="hasPermission('api:dept:update')"
      :can-delete="hasPermission('api:dept:delete')"
      @loaded="handleDeptLoaded"
      @select="handleDeptSelect"
      @refresh="refreshData"
    />

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
              <BusinessTableSummary :total="pagination.total" :page-size="data.length" unit="人" />
            </ElSpace>
          </template>
          <template #right>
            <ArtExcelExport
              :data="employeeExportRows"
              :filename="employeeExportFilename"
              sheet-name="员工列表"
              type="primary"
              size="small"
              plain
              auto-index
            >
              <span class="export-button-content">
                <ArtSvgIcon icon="ri:file-excel-2-line" />
                导出当前页
              </span>
            </ArtExcelExport>
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
          :can-add-position="hasPermission('api:position:add')"
          :can-update-position="hasPermission('api:position:update')"
          :can-delete-position="hasPermission('api:position:delete')"
          :can-add-tag="hasPermission('api:tag:add')"
          :can-update-tag="hasPermission('api:tag:update')"
          :can-delete-tag="hasPermission('api:tag:delete')"
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
  import { fetchGetUserList, fetchGetPositionList } from '@/api/system-manage'
  import { fetchTagList } from '@/api/tag'
  import { fetchDictByTypes } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import UserSearch from './modules/user-search.vue'
  import UserDialog from './modules/user-dialog.vue'
  import UserAssignRoleDialog from './modules/user-assign-role-dialog.vue'
  import DeptSidebar from './modules/dept-sidebar.vue'
  import PositionDialog from '@/views/system/position/modules/position-dialog.vue'
  import TagDialog from '@/views/system/tag/modules/tag-dialog.vue'
  import { ElTag, ElButton, ElSpace } from 'element-plus'
  import { DialogType } from '@/types'
  import { useUserStore } from '@/store/modules/user'
  import BusinessTableSummary from '@/components/business/business-table-summary/index.vue'
  import {
    currentPageExportFilename,
    currentPageExportRows,
    maskedPhoneForExport
  } from '@/utils/current-page-export'

  defineOptions({ name: 'User' })

  const userStore = useUserStore()
  const hasPermission = (perm: string) =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === perm)
  const canQueryDept = computed(() => hasPermission('api:dept:query'))
  const canQueryPosition = computed(() => hasPermission('api:position:query'))
  const canQueryTag = computed(() => hasPermission('api:tag:query'))

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

  const selectedRows = ref<UserListItem[]>([])
  const userPageRef = ref<HTMLElement>()
  const isCompactLayout = ref(false)
  const deptSidebarRef = ref()
  const selectedDeptId = ref<string>()
  // 部门数据（用于表格显示）
  const deptList = ref<{ id: string; deptName: string }[]>([])

  // 以页面内容区而不是浏览器宽度为准：左侧主导航展开后，可用空间会少 230px。
  // 只看 window.innerWidth 会在 1000~1280px 区间误判为宽屏，正是本页此前被挤裂的原因。
  useResizeObserver(userPageRef, ([entry]) => {
    isCompactLayout.value = entry.contentRect.width < 900
  })

  const handleDeptLoaded = (items: { id: string; deptName: string }[]) => {
    deptList.value = items
  }

  const handleDeptSelect = (deptId: string | undefined) => {
    selectedDeptId.value = deptId
    replaceSearchParams({ deptId } as any)
    getData()
  }

  const handleReset = () => {
    resetSearchParams()
    selectedDeptId.value = undefined
    deptSidebarRef.value?.clearSelection()
    getData()
  }

  // 性别选项（字典 20）
  const genderOptions = ref<{ label: string; value: number }[]>([])
  const positionOptions = ref<{ id: string; positionName: string }[]>([])
  const tagOptions = ref<{ id: string; tagName: string }[]>([])

  const getDeptName = (deptId: number | string) => {
    if (!deptId) return '-'
    return deptList.value.find((d) => String(d.id) === String(deptId))?.deptName || '-'
  }

  const getPositionName = (positionId: string | number) => {
    if (!positionId) return '-'
    return (
      positionOptions.value.find((p) => String(p.id) === String(positionId))?.positionName || '-'
    )
  }

  const getTagNames = (tagList: { id: string; tagName: string }[]) => {
    if (!tagList?.length) return h('span', '-')
    return h('div', { class: 'flex flex-wrap gap-1' }, [
      ...tagList.map((t) => h(ElTag, { type: 'info', size: 'small' }, () => t.tagName))
    ])
  }

  const loadReferenceData = async () => {
    const tasks: Promise<unknown>[] = [loadGenderOptions()]
    if (canQueryPosition.value) tasks.push(loadPositionOptions())
    else positionOptions.value = []
    if (canQueryTag.value) tasks.push(loadTagOptions())
    else tagOptions.value = []
    await Promise.all(tasks)
  }

  const loadGenderOptions = async () => {
    const dictResult = await fetchDictByTypes([DictTypeEnum.性别])
    const genderDict = dictResult.find((d) => d.dictType === DictTypeEnum.性别)
    if (genderDict) {
      genderOptions.value = genderDict.dictDataList.map((item) => ({
        label: item.dictLabel,
        value: item.dictValue
      }))
    }
  }

  const searchForm = ref({
    employeeName: undefined,
    deptId: undefined
  })

  const getGenderTag = (gender: number) => {
    const text = getGenderLabel(gender)
    const type = gender === 1 ? ('primary' as const) : ('danger' as const)
    return h(ElTag, { type }, () => text)
  }

  const getGenderLabel = (gender: number) =>
    genderOptions.value.find((o) => o.value === gender)?.label ||
    (gender === 1 ? '男' : gender === 2 ? '女' : '-')

  const getStatusLabel = (disabledFlag: number) => (disabledFlag === 1 ? '启用' : '禁用')

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
          minWidth: 140,
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
                )
            ])
        }
      ]
    }
  })

  const employeeExportFilename = currentPageExportFilename('员工管理')
  const employeeExportRows = computed(() =>
    currentPageExportRows(data.value, [
      { header: '登录账号', value: (row) => row.loginName },
      { header: '用户姓名', value: (row) => row.employeeName },
      { header: '性别', value: (row) => getGenderLabel(row.employeeGender) },
      { header: '手机号', value: (row) => maskedPhoneForExport(row.employeePhone) },
      { header: '部门', value: (row) => getDeptName(row.deptId) },
      { header: '职务', value: (row) => getPositionName(row.positionId) },
      { header: '标签', value: (row) => row.tagList?.map((tag) => tag.tagName).join('、') || '' },
      { header: '状态', value: (row) => getStatusLabel(row.disabledFlag) }
    ])
  )

  const handleSearch = (params: Api.SystemManage.UserSearchParams) => {
    replaceSearchParams(params)
    getData()
  }

  const showDialog = (type: DialogType, row?: UserListItem): void => {
    dialogType.value = type
    currentUserData.value = row || {}
    nextTick(() => {
      dialogVisible.value = true
    })
  }

  const showAssignRoleDialog = (row: UserListItem): void => {
    currentAssignRoleUserData.value = row
    assignRoleDialogVisible.value = true
  }

  const handleDialogSubmit = async () => {
    dialogVisible.value = false
    currentUserData.value = {}
    await loadReferenceData()
    refreshData()
  }

  const handleOpenPositionDialog = (data?: Partial<Api.SystemManage.PositionListItem>) => {
    positionDialogType.value = data?.id ? 'edit' : 'add'
    currentPositionData.value = data || {}
    positionDialogVisible.value = true
  }

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

  const handleOpenTagDialog = (data?: Partial<Api.SystemManage.TagListItem>) => {
    tagDialogType.value = data?.id ? 'edit' : 'add'
    currentTagData.value = data || {}
    tagDialogVisible.value = true
  }

  const handleTagSubmit = async () => {
    tagDialogVisible.value = false
    currentTagData.value = {}
    await loadTagOptions()
  }

  const loadPositionOptions = async () => {
    if (!canQueryPosition.value) {
      positionOptions.value = []
      return
    }
    const positionRes = await fetchGetPositionList({ current: 1, size: 9999 })
    if (positionRes.list) {
      positionOptions.value = positionRes.list
    }
  }

  const loadTagOptions = async () => {
    if (!canQueryTag.value) {
      tagOptions.value = []
      return
    }
    const tagRes = await fetchTagList({})
    tagOptions.value = tagRes || []
  }

  const handleSelectionChange = (selection: UserListItem[]): void => {
    selectedRows.value = selection
  }

  onMounted(() => {
    loadReferenceData()
  })
</script>

<style scoped lang="scss">
  .user-page {
    display: flex;
    gap: 12px;
    height: 100%;
    min-width: 0;
  }

  .main-content {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 12px;
    min-width: 0;
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

  .export-button-content {
    display: inline-flex;
    gap: 6px;
    align-items: center;
  }
</style>
