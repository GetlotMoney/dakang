<!-- 水种套餐——水种管理（REQ-073：启停/排序/默认水种统一维护，8 种水占位待甲方确认） -->
<template>
  <div class="water-page art-full-height">
    <BusinessModuleNav module-key="product" />

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
          <ElButton v-if="hasPermission('product:water:add')" @click="showDialog('add')" v-ripple
            >新增水种</ElButton
          >
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
      >
      </ArtTable>

      <!-- 新增/编辑弹窗 -->
      <ElDialog
        v-model="dialogVisible"
        :title="dialogType === 'add' ? '新增水种' : '编辑水种'"
        width="480px"
        align-center
      >
        <ElForm ref="formRef" :model="formData" :rules="rules" label-width="90px">
          <ElFormItem label="水种名称" prop="waterName">
            <ElInput
              v-model="formData.waterName"
              placeholder="如 纯净水"
              maxlength="20"
              show-word-limit
            />
          </ElFormItem>
          <ElFormItem label="排序" prop="waterSort">
            <ElInputNumber v-model="formData.waterSort" :min="1" :max="999" />
          </ElFormItem>
          <ElFormItem label="默认水种" prop="defaultFlag">
            <ElSwitch
              v-model="formData.defaultFlag"
              :active-value="2"
              :inactive-value="1"
              active-text="是"
              inactive-text="否"
            />
            <ElTooltip content="全局仅一个默认水种，设为默认将自动取消其他水种的默认标记">
              <ElIcon class="ml-2 text-secondary"><QuestionFilled /></ElIcon>
            </ElTooltip>
          </ElFormItem>
          <ElFormItem label="状态" prop="waterStatus">
            <ElRadioGroup v-model="formData.waterStatus">
              <ElRadio :value="1">正常</ElRadio>
              <ElRadio :value="2">禁用</ElRadio>
            </ElRadioGroup>
          </ElFormItem>
          <ElFormItem label="说明" prop="waterDesc">
            <ElInput
              v-model="formData.waterDesc"
              type="textarea"
              :rows="2"
              placeholder="选填"
              maxlength="500"
            />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <div class="dialog-footer">
            <ElButton @click="dialogVisible = false">取消</ElButton>
            <ElButton type="primary" :loading="submitLoading" @click="handleSubmit">提交</ElButton>
          </div>
        </template>
      </ElDialog>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { h } from 'vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import { ElMessage, ElMessageBox, ElTag } from 'element-plus'
  import { QuestionFilled } from '@element-plus/icons-vue'
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import {
    fetchWaterTypePage,
    fetchAddWaterType,
    fetchUpdateWaterType,
    fetchDeleteWaterType,
    type WaterTypeItem
  } from '@/api/product'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { useUserStore } from '@/store/modules/user'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'

  defineOptions({ name: 'ProductWater' })

  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  const searchBarRef = ref()
  const dialogVisible = ref(false)
  const dialogType = ref<'add' | 'edit'>('add')
  const submitLoading = ref(false)
  const formRef = ref<FormInstance>()

  const searchForm = ref({
    waterName: undefined as string | undefined,
    waterStatus: undefined as number | undefined
  })

  const statusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    statusOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.禁用状态))
  })

  const searchItems = computed(() => [
    {
      label: '水种名称',
      key: 'waterName',
      type: 'input',
      placeholder: '请输入水种名称',
      clearable: true
    },
    {
      label: '状态',
      key: 'waterStatus',
      type: 'select',
      placeholder: '请选择状态',
      clearable: true,
      options: statusOptions.value
    }
  ])

  const formData = reactive({
    id: undefined as number | undefined,
    waterName: '',
    waterSort: 1,
    defaultFlag: 1,
    waterStatus: 1,
    waterDesc: ''
  })

  const rules: FormRules = {
    waterName: [{ required: true, message: '请输入水种名称', trigger: 'blur' }],
    waterSort: [{ required: true, message: '请输入排序', trigger: 'blur' }]
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
      apiFn: fetchWaterTypePage,
      apiParams: {
        current: 1,
        size: 20,
        ...searchForm.value
      },
      columnsFactory: () => [
        { prop: 'waterName', label: '水种名称', minWidth: 130 },
        { prop: 'waterSort', label: '排序', width: 80 },
        {
          prop: 'defaultFlag',
          label: '默认水种',
          width: 100,
          formatter: (row: WaterTypeItem) =>
            row.defaultFlag === 2 ? h(ElTag, { type: 'primary' }, () => '默认') : '—'
        },
        {
          prop: 'waterStatus',
          label: '状态',
          width: 90,
          formatter: (row: WaterTypeItem) =>
            h(ElTag, { type: row.waterStatus === 1 ? 'success' : 'danger' }, () =>
              row.waterStatus === 1 ? '正常' : '禁用'
            )
        },
        {
          prop: 'outletRefCount',
          label: '出水口引用',
          width: 110,
          formatter: (row: WaterTypeItem) => `${row.outletRefCount ?? 0} 个`
        },
        {
          prop: 'waterDesc',
          label: '说明',
          minWidth: 200,
          showOverflowTooltip: true,
          formatter: (row: WaterTypeItem) => row.waterDesc || '-'
        },
        {
          prop: 'operation',
          label: '操作',
          width: 140,
          fixed: 'right',
          formatter: (row: WaterTypeItem) =>
            h('div', [
              hasPermission('product:water:update') &&
                h(ArtButtonTable, {
                  type: 'edit',
                  onClick: () => showDialog('edit', row)
                }),
              hasPermission('product:water:delete') &&
                h(ArtButtonTable, {
                  type: 'delete',
                  onClick: () => handleDelete(row)
                })
            ])
        }
      ]
    }
  })

  function showDialog(type: 'add' | 'edit', row?: WaterTypeItem) {
    dialogType.value = type
    if (type === 'edit' && row) {
      Object.assign(formData, {
        id: row.id,
        waterName: row.waterName,
        waterSort: row.waterSort,
        defaultFlag: row.defaultFlag,
        waterStatus: row.waterStatus,
        waterDesc: row.waterDesc || ''
      })
    } else {
      Object.assign(formData, {
        id: undefined,
        waterName: '',
        waterSort: 1,
        defaultFlag: 1,
        waterStatus: 1,
        waterDesc: ''
      })
    }
    dialogVisible.value = true
    nextTick(() => formRef.value?.clearValidate())
  }

  async function handleSubmit() {
    await formRef.value?.validate()
    // 默认水种禁用拦截（后端硬校验，前端提前拦提升体验）
    if (formData.defaultFlag === 2 && formData.waterStatus === 2) {
      ElMessage.warning('默认水种不允许禁用，请先取消默认')
      return
    }
    submitLoading.value = true
    try {
      const payload = { ...formData } as any
      if (dialogType.value === 'add') {
        await fetchAddWaterType(payload)
        ElMessage.success('新增成功')
      } else {
        await fetchUpdateWaterType(payload)
        ElMessage.success('修改成功')
      }
      dialogVisible.value = false
      getData()
    } finally {
      submitLoading.value = false
    }
  }

  function handleDelete(row: WaterTypeItem) {
    ElMessageBox.confirm(
      `确认删除水种「${row.waterName}」？须先禁用且无出水口引用（当前引用 ${row.outletRefCount ?? 0} 个）。`,
      '删除确认',
      { type: 'warning' }
    ).then(async () => {
      await fetchDeleteWaterType(row.id)
      ElMessage.success('删除成功')
      getData()
    })
  }

  const handleSearch = (params: Record<string, any>) => {
    replaceSearchParams(params)
    void getData()
  }

  const handleReset = () => {
    resetSearchParams()
  }
</script>

<style scoped lang="scss">
  .water-page {
    display: flex;
    flex-direction: column;
  }
</style>
