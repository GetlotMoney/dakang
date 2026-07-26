<template>
  <ElDrawer
    v-model="drawerVisible"
    :title="`标签「${props.tagData?.tagName}」的用户列表`"
    size="60%"
    direction="rtl"
  >
    <!-- 搜索栏 + 添加用户：ArtSearchBar 只按表单项 key 渲染插槽、无 append 出口，
         故按钮作为搜索栏同级兄弟独立摆放（与列表页“新增”按钮统一由容器承载的约定一致）。 -->
    <div class="employee-search">
      <ArtSearchBar
        ref="searchBarRef"
        class="employee-search__bar"
        v-model="searchForm"
        :items="formItems"
        auto-search
        :rules="rules"
        @reset="handleReset"
        @search="handleSearch"
      />
      <ElButton
        v-if="hasPermission('api:tag:update')"
        type="primary"
        class="employee-search__add"
        @click="showAddEmployee"
        >添加用户</ElButton
      >
    </div>

    <!-- 用户表格 -->
    <ArtTable
      ref="tableRef"
      row-key="id"
      :loading="loading"
      :columns="getColumnsWithOperation"
      :data="tableData"
      :pagination="pagination"
      @pagination:size-change="handleSizeChange"
      @pagination:current-change="handleCurrentChange"
    >
    </ArtTable>

    <!-- 添加用户弹窗 -->
    <ElDialog v-model="addDialogVisible" title="添加用户" width="40%" align-center>
      <ElForm ref="addFormRef" :model="addFormData" label-width="80px">
        <ElFormItem label="选择用户" prop="employeeIds">
          <ElSelect
            v-model="addFormData.employeeIds"
            placeholder="请选择用户"
            multiple
            filterable
            class="w-full"
          >
            <ElOption
              v-for="emp in allEmployees"
              :key="emp.id"
              :label="emp.employeeName"
              :value="emp.id"
            />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <div class="dialog-footer">
          <ElButton @click="addDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="submitLoading" @click="handleAddEmployee"
            >提交</ElButton
          >
        </div>
      </template>
    </ElDialog>
  </ElDrawer>
</template>

<script setup lang="ts">
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import ArtSearchBar from '@/components/core/forms/art-search-bar/index.vue'
  import { fetchTagEmployeePage, fetchDeleteTagEmployee, fetchAddEmployeesToTag } from '@/api/tag'
  import { fetchGetUserList } from '@/api/system-manage'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'

  interface Props {
    visible: boolean
    tagData?: Partial<Api.SystemManage.TagListItem>
  }

  interface Emits {
    (e: 'update:visible', value: boolean): void
    (e: 'submit'): void
  }

  const props = defineProps<Props>()
  const emit = defineEmits<Emits>()

  // 与页面其它变更按钮一致，按后端菜单权限门控；标签成员维护归属“标签更新”语义。
  const hasPermission = (perm: string) =>
    useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)

  const loading = ref(false)
  const submitLoading = ref(false)

  const drawerVisible = computed({
    get: () => props.visible,
    set: (value) => emit('update:visible', value)
  })

  // 搜索
  const searchBarRef = ref()
  const searchForm = ref({
    employeeName: ''
  })

  const formItems = computed(() => [
    {
      label: '用户姓名',
      key: 'employeeName',
      type: 'input',
      placeholder: '请输入用户姓名',
      clearable: true
    }
  ])

  const rules = {}

  // 分页
  const pagination = ref({
    current: 1,
    size: 10,
    total: 0
  })

  // 用户数据
  const tableData = ref<Api.SystemManage.TagEmployeeItem[]>([])

  // 添加用户弹窗
  const addDialogVisible = ref(false)
  const addFormRef = ref()
  const addFormData = reactive({
    employeeIds: [] as string[]
  })
  const allEmployees = ref<Api.SystemManage.UserListItem[]>([])

  // 获取标签下用户列表
  const getTagEmployeeList = async (): Promise<void> => {
    if (!props.tagData?.id) return
    loading.value = true
    try {
      const params = {
        tagId: props.tagData.id,
        current: pagination.value.current,
        size: pagination.value.size,
        employeeName: searchForm.value.employeeName || undefined
      }
      const res = await fetchTagEmployeePage(params)
      tableData.value = res?.list || []
      pagination.value.total = Number(res?.total) || 0
    } catch (error) {
      throw error instanceof Error ? error : new Error('获取用户列表失败')
    } finally {
      loading.value = false
    }
  }

  // 搜索
  const handleSearch = (params: { employeeName?: string }) => {
    searchForm.value.employeeName = params.employeeName || ''
    pagination.value.current = 1
    getTagEmployeeList()
  }

  // 重置
  const handleReset = () => {
    searchForm.value.employeeName = ''
    pagination.value.current = 1
    getTagEmployeeList()
  }

  // 分页变化
  const handleSizeChange = (size: number) => {
    pagination.value.size = size
    pagination.value.current = 1
    getTagEmployeeList()
  }

  const handleCurrentChange = (current: number) => {
    pagination.value.current = current
    getTagEmployeeList()
  }

  // 表格列
  const columns = [
    { prop: 'employeeName', label: '用户姓名', minWidth: 120 },
    { prop: 'employeePhone', label: '手机号', minWidth: 120 },
    {
      prop: 'employeeGender',
      label: '性别',
      width: 80,
      formatter: (row: Api.SystemManage.TagEmployeeItem) => {
        return row.employeeGender === 1 ? '男' : row.employeeGender === 2 ? '女' : '-'
      }
    },
    { prop: 'operation', label: '操作', width: 100, fixed: 'right' as const }
  ]

  // 动态添加操作列按钮
  const getColumnsWithOperation = computed(() => [
    ...columns.slice(0, -1),
    {
      prop: 'operation',
      label: '操作',
      width: 100,
      fixed: 'right' as const,
      formatter: (row: Api.SystemManage.TagEmployeeItem) =>
        h('div', [
          h(ArtButtonTable, {
            type: 'delete',
            onClick: () => removeEmployee(row)
          })
        ])
    }
  ])

  // 移除用户关联
  const removeEmployee = (row: Api.SystemManage.TagEmployeeItem): void => {
    ElMessageBox.confirm('确定要从该标签移除该用户吗？', '移除用户', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(async () => {
      try {
        await fetchDeleteTagEmployee({
          tagId: props.tagData!.id!,
          employeeId: row.id
        })
        ElMessage.success('移除成功')
        getTagEmployeeList()
      } catch {
        ElMessage.error('移除失败')
      }
    })
  }

  // 显示添加用户弹窗
  const showAddEmployee = async () => {
    addDialogVisible.value = true
    addFormData.employeeIds = []
    const res = await fetchGetUserList({ current: 1, size: 9999 })
    allEmployees.value = res?.list || []
  }

  // 按 employeeId 反查姓名，用于失败明细提示
  const employeeNameOf = (id: string): string =>
    allEmployees.value.find((emp) => String(emp.id) === String(id))?.employeeName || String(id)

  // 提交添加用户：后端仅单条接口，批量由 fetchAddEmployeesToTag 逐个提交并聚合结果
  const handleAddEmployee = async () => {
    if (!addFormData.employeeIds.length) {
      ElMessage.warning('请选择用户')
      return
    }
    submitLoading.value = true
    try {
      const res = await fetchAddEmployeesToTag(props.tagData!.id!, addFormData.employeeIds)
      const parts: string[] = []
      if (res.success.length) parts.push(`成功添加 ${res.success.length} 人`)
      if (res.skipped.length) parts.push(`${res.skipped.length} 人已关联跳过`)
      // 至少有一条新增或已关联即视为“有结果”，据此决定是否关闭弹窗并刷新
      const changed = res.success.length > 0 || res.skipped.length > 0

      if (res.failed.length) {
        const detail = res.failed
          .map((item) => `${employeeNameOf(item.employeeId)}（${item.reason}）`)
          .join('；')
        if (changed) {
          ElMessage.warning(`${parts.join('，')}；${res.failed.length} 人失败：${detail}`)
        } else {
          // 全部失败：明确提示且不刷新、不关闭弹窗，便于重试
          ElMessage.error(`添加失败：${detail}`)
        }
      } else {
        ElMessage.success(parts.join('，') || '无变更')
      }

      if (changed) {
        addDialogVisible.value = false
        getTagEmployeeList()
      }
    } finally {
      submitLoading.value = false
    }
  }

  watch(
    () => props.visible,
    (visible) => {
      if (visible) {
        pagination.value.current = 1
        pagination.value.total = 0
        tableData.value = []
        getTagEmployeeList()
      }
    }
  )
</script>

<style scoped lang="scss">
  .w-full {
    width: 100%;
  }

  .employee-search {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    margin-bottom: 12px;

    &__bar {
      flex: 1;
      min-width: 0;
    }

    &__add {
      flex-shrink: 0;
    }
  }
</style>
