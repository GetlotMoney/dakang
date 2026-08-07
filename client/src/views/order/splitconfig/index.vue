<!-- 分账比例配置（E2E-08 包E）：版本化——新增生效版本而非改历史；变更受 finance:config:edit 权限门 -->
<template>
  <div class="art-full-height">
    <BusinessModuleNav module-key="order" />

    <ElAlert
      type="warning"
      :closable="false"
      show-icon
      title="修改比例只影响生效后的新单"
      class="mb-3"
    />
    <!-- 全仓统一搜索形态：auto-search 选择即防抖查询，无独立查询按钮；动作按钮归表头标准位 -->
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
          <ElButton
            v-if="hasPermission('finance:config:edit')"
            type="primary"
            @click="createVisible = true"
            v-ripple
            >新增生效版本</ElButton
          >
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

    <ElDialog v-model="createVisible" title="新增比例生效版本" width="460px">
      <ElForm label-width="90px">
        <ElFormItem label="商品线" required>
          <ElSelect v-model="createForm.productLine" style="width: 100%">
            <ElOption
              v-for="opt in lineOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="收款方" required>
          <ElSelect v-model="createForm.receiverType" style="width: 100%">
            <ElOption
              v-for="opt in receiverOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="比例(%)" required>
          <ElInputNumber
            v-model="createForm.percent"
            :min="0"
            :max="100"
            :precision="2"
            style="width: 100%"
          />
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput v-model="createForm.remark" maxlength="100" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="createVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="creating" @click="handleCreate">生效（自当下）</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchSplitConfigCreate, fetchSplitConfigPage, type SplitConfigItem } from '@/api/finance'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { bpToPercentText, percentToBp } from '@/utils/format'
  import { useUserStore } from '@/store/modules/user'
  import {
    ElAlert,
    ElButton,
    ElDialog,
    ElForm,
    ElFormItem,
    ElInput,
    ElInputNumber,
    ElMessage,
    ElOption,
    ElSelect
  } from 'element-plus'
  import dayjs from 'dayjs'

  defineOptions({ name: 'OrderSplitConfig' })

  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  const searchForm = ref<{ productLine?: number }>({})
  const lineOptions = ref<{ label: string; value: number }[]>([])
  const receiverOptions = ref<{ label: string; value: number }[]>([])
  const createVisible = ref(false)
  const creating = ref(false)
  const createForm = ref<{
    productLine?: number
    receiverType?: number
    percent?: number
    remark?: string
  }>({})

  onMounted(async () => {
    const [lines, receivers] = await Promise.all([
      fetchDictOptions(DictTypeEnum.分账商品线),
      fetchDictOptions(DictTypeEnum.分账收款方类型)
    ])
    lineOptions.value = toDictOptions(lines)
    receiverOptions.value = toDictOptions(receivers)
  })

  const dictLabel = (options: { label: string; value: number }[], value: number) =>
    options.find((o) => o.value === value)?.label ?? String(value)

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
      apiFn: fetchSplitConfigPage,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        {
          prop: 'productLine',
          label: '商品线',
          width: 100,
          formatter: (row: SplitConfigItem) => dictLabel(lineOptions.value, row.productLine)
        },
        {
          prop: 'receiverType',
          label: '收款方',
          width: 110,
          formatter: (row: SplitConfigItem) => dictLabel(receiverOptions.value, row.receiverType)
        },
        {
          prop: 'splitRate',
          label: '比例',
          width: 100,
          formatter: (row: SplitConfigItem) => bpToPercentText(row.splitRate)
        },
        {
          prop: 'effectTime',
          label: '生效时间',
          minWidth: 170,
          formatter: (row: SplitConfigItem) =>
            dayjs(row.effectTime, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss')
        },
        { prop: 'configRemark', label: '备注', minWidth: 160 }
      ]
    }
  })

  const searchItems = computed(() => [
    {
      label: '商品线',
      key: 'productLine',
      type: 'select',
      placeholder: '商品线',
      clearable: true,
      options: lineOptions.value
    }
  ])

  const handleSearch = () => {
    replaceSearchParams({ ...searchForm.value })
    getData()
  }

  const handleReset = () => {
    searchForm.value = {}
    replaceSearchParams({})
    getData()
  }

  const handleCreate = async () => {
    const form = createForm.value
    if (form.productLine == null || form.receiverType == null || form.percent == null) {
      ElMessage.warning('商品线/收款方/比例均必填')
      return
    }
    creating.value = true
    try {
      // 百分比 → 万分比（服务端口径）；生效时间由服务端取当下，不允许写过去时点
      await fetchSplitConfigCreate({
        productLine: form.productLine,
        receiverType: form.receiverType,
        splitRate: percentToBp(form.percent),
        remark: form.remark
      })
      ElMessage.success('新版本已生效')
      createVisible.value = false
      createForm.value = {}
      getData()
    } finally {
      creating.value = false
    }
  }
</script>
