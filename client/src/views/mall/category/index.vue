<!-- 商城分类管理（E2E-09 S1，R1-P1-1/P2-2 改仓库规范组件）：列表、排序、启停；编码不可改，停用分类下商品不得新上架 -->
<template>
  <div class="art-full-height">
    <BusinessModuleNav module-key="mall" />

    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      auto-search
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElButton v-if="canEdit" type="primary" @click="openCreate" v-ripple>新增分类</ElButton>
        </template>
      </ArtTableHeader>

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

    <ElDialog v-model="dialogVisible" :title="editing ? '编辑分类' : '新增分类'" width="460px">
      <ElForm ref="formRef" :model="form" :rules="rules" label-width="90px">
        <ElFormItem label="分类编码" prop="categoryCode">
          <ElInput
            v-model="form.categoryCode"
            :disabled="editing"
            placeholder="唯一编码，创建后不可改"
          />
        </ElFormItem>
        <ElFormItem label="分类名称" prop="categoryName">
          <ElInput v-model="form.categoryName" placeholder="请输入分类名称" />
        </ElFormItem>
        <ElFormItem label="排序" prop="categorySort">
          <ElInputNumber v-model="form.categorySort" :min="0" :max="9999" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="submit">保存</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import {
    fetchMallCategoryPage,
    fetchMallCategorySave,
    fetchMallCategoryUpdate,
    fetchMallCategoryChangeStatus,
    type MallCategoryItem
  } from '@/api/mall'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { hasPermission } from '@/utils/permission'
  import {
    ElButton,
    ElMessage,
    ElMessageBox,
    ElTag,
    type FormInstance,
    type FormRules
  } from 'element-plus'

  defineOptions({ name: 'MallCategory' })

  /** 新增、改名改序与启停同属分类维护：停用分类会挡住其下商品上架，与编辑同权。 */
  const canEdit = computed(() => hasPermission('mall:category:edit'))

  const searchForm = ref<{ keyword?: string; status?: number }>({})
  const statusOptions = ref<{ label: string; value: number }[]>([])

  const searchItems = computed(() => [
    { label: '分类名称', key: 'keyword', type: 'input', placeholder: '分类名称', clearable: true },
    {
      label: '状态',
      key: 'status',
      type: 'select',
      placeholder: '全部',
      clearable: true,
      options: statusOptions.value
    }
  ])

  onMounted(async () => {
    statusOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.商城分类状态))
  })

  const statusLabel = (v: number) =>
    statusOptions.value.find((o) => o.value === v)?.label ?? String(v)

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchMallCategoryPage,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'categoryCode', label: '分类编码', width: 160 },
        { prop: 'categoryName', label: '分类名称', minWidth: 160 },
        { prop: 'categorySort', label: '排序', width: 90, align: 'center' },
        {
          prop: 'categoryStatus',
          label: '状态',
          width: 100,
          align: 'center',
          formatter: (row: MallCategoryItem) =>
            h(ElTag, { type: row.categoryStatus === 1 ? 'success' : 'info' }, () =>
              statusLabel(row.categoryStatus)
            )
        },
        {
          prop: 'operation',
          label: '操作',
          width: 180,
          fixed: 'right',
          formatter: (row: MallCategoryItem) =>
            h('div', [
              canEdit.value &&
                h(
                  ElButton,
                  { link: true, type: 'primary', onClick: () => openEdit(row) },
                  () => '编辑'
                ),
              canEdit.value &&
                h(
                  ElButton,
                  {
                    link: true,
                    type: row.categoryStatus === 1 ? 'warning' : 'success',
                    onClick: () => toggleStatus(row)
                  },
                  () => (row.categoryStatus === 1 ? '停用' : '启用')
                )
            ])
        }
      ]
    }
  })

  const handleSearch = () => {
    replaceSearchParams({ ...searchForm.value, keyword: searchForm.value.keyword || undefined })
    getData()
  }

  const handleReset = () => {
    searchForm.value = {}
    replaceSearchParams({})
    getData()
  }

  const dialogVisible = ref(false)
  const editing = ref(false)
  const saving = ref(false)
  const formRef = ref<FormInstance>()
  const form = ref<{
    id?: string
    categoryCode: string
    categoryName: string
    categorySort: number
  }>({
    categoryCode: '',
    categoryName: '',
    categorySort: 0
  })
  const rules: FormRules = {
    categoryCode: [{ required: true, message: '请填写分类编码', trigger: 'blur' }],
    categoryName: [{ required: true, message: '请填写分类名称', trigger: 'blur' }],
    categorySort: [{ required: true, message: '请填写排序号', trigger: 'blur' }]
  }

  const openCreate = () => {
    editing.value = false
    form.value = { categoryCode: '', categoryName: '', categorySort: 0 }
    dialogVisible.value = true
  }

  const openEdit = (row: MallCategoryItem) => {
    editing.value = true
    form.value = {
      id: row.id,
      categoryCode: row.categoryCode,
      categoryName: row.categoryName,
      categorySort: row.categorySort
    }
    dialogVisible.value = true
  }

  const submit = async () => {
    await formRef.value?.validate()
    saving.value = true
    try {
      if (editing.value && form.value.id) {
        await fetchMallCategoryUpdate({
          id: form.value.id,
          categoryName: form.value.categoryName,
          categorySort: form.value.categorySort
        })
      } else {
        await fetchMallCategorySave({
          categoryCode: form.value.categoryCode.trim(),
          categoryName: form.value.categoryName.trim(),
          categorySort: form.value.categorySort
        })
      }
      ElMessage.success('保存成功')
      dialogVisible.value = false
      refreshData()
    } finally {
      saving.value = false
    }
  }

  const toggleStatus = async (row: MallCategoryItem) => {
    const target = row.categoryStatus === 1 ? 2 : 1
    await ElMessageBox.confirm(
      target === 2 ? '停用后该分类下的商品将不能再上架，确认停用？' : '确认启用该分类？',
      '提示',
      { type: 'warning' }
    )
    await fetchMallCategoryChangeStatus({ id: row.id, targetStatus: target })
    ElMessage.success('操作成功')
    refreshData()
  }
</script>
