<!-- 日对账（E2E-08 包E）：批任务 + 差异台账；手动触发受 finance:reconcile:run 权限门 -->
<template>
  <div class="art-full-height">
    <BusinessModuleNav module-key="order" />

    <ElCard class="art-table-card mb-3">
      <template #header>
        <ElSpace wrap>
          <span>对账批任务</span>
          <ElInput v-model="runDate" placeholder="账期 yyyyMMdd" clearable style="width: 150px" />
          <ElButton
            v-if="hasPermission('finance:reconcile:run')"
            type="primary"
            :loading="running"
            @click="handleRun"
            >触发对账</ElButton
          >
          <span class="hint-text">同账期重跑会替换该账期原有的差异记录</span>
        </ElSpace>
      </template>
      <ArtTable
        :loading="taskLoading"
        :data="taskData"
        :columns="taskColumns"
        :pagination="taskPagination"
        @pagination:size-change="taskSizeChange"
        @pagination:current-change="taskCurrentChange"
      >
      </ArtTable>
    </ElCard>

    <!-- 差异台账筛选：全仓统一 auto-search 形态（上方「触发对账」是动作不是筛选，保持显式按钮） -->
    <ArtSearchBar
      v-model="diffForm"
      :items="diffSearchItems"
      auto-search
      @search="diffSearch"
      @reset="diffReset"
    />

    <ElCard class="art-table-card">
      <template #header>
        <span>差异台账</span>
      </template>
      <ArtTable
        :loading="diffLoading"
        :data="diffData"
        :columns="diffColumns"
        :pagination="diffPagination"
        @pagination:size-change="diffSizeChange"
        @pagination:current-change="diffCurrentChange"
      >
      </ArtTable>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import {
    fetchReconcileDiffPage,
    fetchReconcileRun,
    fetchReconcileTaskPage,
    type ReconcileDiffItem,
    type ReconcileTaskItem
  } from '@/api/finance'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { useUserStore } from '@/store/modules/user'
  import { ElButton, ElCard, ElInput, ElMessage, ElSpace, ElTag } from 'element-plus'

  defineOptions({ name: 'OrderReconcile' })

  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  const runDate = ref('')
  const running = ref(false)
  const diffForm = ref<{ bizDate?: string; diffType?: number }>({})
  const diffTypeOptions = ref<{ label: string; value: number }[]>([])
  const taskStatusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [diffTypes, taskStatuses] = await Promise.all([
      fetchDictOptions(DictTypeEnum.对账差异分类),
      fetchDictOptions(DictTypeEnum.对账任务状态)
    ])
    diffTypeOptions.value = toDictOptions(diffTypes)
    taskStatusOptions.value = toDictOptions(taskStatuses)
  })

  const dictLabel = (options: { label: string; value: number }[], value: number) =>
    options.find((o) => o.value === value)?.label ?? String(value)

  const {
    columns: taskColumns,
    data: taskData,
    loading: taskLoading,
    pagination: taskPagination,
    getData: taskGetData,
    handleSizeChange: taskSizeChange,
    handleCurrentChange: taskCurrentChange
  } = useTable({
    core: {
      apiFn: fetchReconcileTaskPage,
      apiParams: { current: 1, size: 10 },
      columnsFactory: () => [
        { prop: 'bizDate', label: '账期', width: 120 },
        {
          prop: 'taskStatus',
          label: '状态',
          width: 100,
          formatter: (row: ReconcileTaskItem) =>
            h(
              ElTag,
              {
                type: row.taskStatus === 2 ? 'success' : row.taskStatus === 3 ? 'danger' : 'warning'
              },
              () => dictLabel(taskStatusOptions.value, row.taskStatus)
            )
        },
        { prop: 'checkTotal', label: '核对项', width: 100 },
        { prop: 'diffTotal', label: '差异数', width: 100 },
        { prop: 'updateTime', label: '最近执行', minWidth: 150 }
      ]
    }
  })

  const {
    columns: diffColumns,
    data: diffData,
    loading: diffLoading,
    pagination: diffPagination,
    getData: diffGetData,
    replaceSearchParams: diffReplace,
    handleSizeChange: diffSizeChange,
    handleCurrentChange: diffCurrentChange
  } = useTable({
    core: {
      apiFn: fetchReconcileDiffPage,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'bizDate', label: '账期', width: 110 },
        {
          prop: 'diffType',
          label: '分类',
          width: 110,
          formatter: (row: ReconcileDiffItem) =>
            h(ElTag, { type: 'danger' }, () => dictLabel(diffTypeOptions.value, row.diffType))
        },
        { prop: 'checkDimension', label: '核对维度', width: 130 },
        { prop: 'bizKey', label: '业务键', minWidth: 160 },
        { prop: 'expectedVal', label: '期望值', minWidth: 130 },
        { prop: 'actualVal', label: '实际值', minWidth: 130 },
        { prop: 'diffRemark', label: '说明', minWidth: 160 }
      ]
    }
  })

  const handleRun = async () => {
    const date = runDate.value.trim()
    if (!/^\d{8}$/.test(date)) {
      ElMessage.warning('账期必须是 yyyyMMdd')
      return
    }
    running.value = true
    try {
      const task = await fetchReconcileRun(date)
      ElMessage.success(`对账完成：核对 ${task.checkTotal} 项，差异 ${task.diffTotal} 项`)
      taskGetData()
      diffReplace({ bizDate: date })
      diffGetData()
    } finally {
      running.value = false
    }
  }

  const diffSearchItems = computed(() => [
    { label: '账期', key: 'bizDate', type: 'input', placeholder: '账期 yyyyMMdd', clearable: true },
    {
      label: '差异分类',
      key: 'diffType',
      type: 'select',
      placeholder: '差异分类',
      clearable: true,
      options: diffTypeOptions.value
    }
  ])

  const diffSearch = () => {
    diffReplace({ ...diffForm.value })
    diffGetData()
  }

  const diffReset = () => {
    diffForm.value = {}
    diffReplace({})
    diffGetData()
  }
</script>

<style scoped>
  .hint-text {
    font-size: 12px;
    color: var(--art-text-gray-500);
  }
</style>
