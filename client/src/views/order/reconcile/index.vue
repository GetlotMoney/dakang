<!-- 日对账（E2E-08 包E）：批任务=每个账期的核对事实，差异台账=需人工核对队列；
     手动触发受 finance:reconcile:run 权限门。差异行按业务键解析出可追溯对象，解析不出就不给跳转。 -->
<template>
  <div class="art-full-height">
    <BusinessModuleNav module-key="finance" />

    <ElCard class="art-table-card mb-3">
      <template #header>
        <ElSpace wrap>
          <span>对账批任务</span>
          <!-- 账期与下方筛选同为日期选择器：手敲 14 位/8 位格式串是开发记法，运营敲不出来 -->
          <ElDatePicker
            v-model="runDate"
            type="date"
            placeholder="选择账期"
            value-format="YYYYMMDD"
            clearable
            style="width: 150px"
          />
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
        <ElSpace wrap>
          <span>差异台账</span>
          <ElTag type="danger" size="small" effect="plain">需人工核对</ElTag>
          <span class="hint-text">每行都是一处账实不符，核对后由对应业务页处理</span>
        </ElSpace>
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
    parseReconcileBizKey,
    ReconcileTaskStatus,
    type ReconcileDiffItem,
    type ReconcileTaskItem
  } from '@/api/finance'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { useUserStore } from '@/store/modules/user'
  import { ElButton, ElCard, ElDatePicker, ElMessage, ElSpace, ElTag } from 'element-plus'

  defineOptions({ name: 'OrderReconcile' })

  const router = useRouter()

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

  /**
   * 核对维度是差异的产生位置，界面必须说人话。五个键与服务端每轮实际跑的五个维度一一对应，
   * 少登记一个，那一类差异行就把英文串直接摆给运营看。
   * 未登记的维度原样带出：宁可显示原值，也不把一个新维度错标成已知的那几种。
   */
  const DIMENSION_LABELS: Record<string, string> = {
    'payment-fact': '支付事实',
    'order-flow': '订单流水',
    'card-ledger': '水卡账本',
    'income-ledger': '收益账本',
    'split-sum': '分账合计'
  }
  const dimensionLabel = (dimension?: string) =>
    (dimension && DIMENSION_LABELS[dimension]) || dimension || '—'

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
                type:
                  row.taskStatus === ReconcileTaskStatus.BALANCED
                    ? 'success'
                    : row.taskStatus === ReconcileTaskStatus.DIFF
                      ? 'danger'
                      : 'warning'
              },
              () => dictLabel(taskStatusOptions.value, row.taskStatus)
            )
        },
        { prop: 'checkTotal', label: '核对项', width: 100 },
        {
          prop: 'diffTotal',
          label: '差异数',
          width: 110,
          // 有差异的账期一键把下方台账切到该账期，运营不必再手敲一遍账期
          formatter: (row: ReconcileTaskItem) =>
            row.diffTotal > 0
              ? h(
                  ElButton,
                  {
                    type: 'danger',
                    link: true,
                    size: 'small',
                    onClick: () => focusDiffs(row.bizDate)
                  },
                  () => String(row.diffTotal)
                )
              : '0'
        },
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
        {
          prop: 'checkDimension',
          label: '核对位置',
          width: 120,
          formatter: (row: ReconcileDiffItem) => dimensionLabel(row.checkDimension)
        },
        {
          prop: 'bizKey',
          label: '关联对象',
          minWidth: 210,
          formatter: (row: ReconcileDiffItem) => renderBizKey(row)
        },
        { prop: 'expectedVal', label: '期望值', minWidth: 130 },
        { prop: 'actualVal', label: '实际值', minWidth: 130 },
        { prop: 'diffRemark', label: '说明', minWidth: 200, showOverflowTooltip: true }
      ]
    }
  })

  /**
   * 关联对象由业务键与核对位置互相印证后解析（见 parseReconcileBizKey）：
   * 只有解析成订单号才给跳转；水卡与收益账户当前没有对应的只读页面，如实显示对象而不给死链接。
   */
  function renderBizKey(row: ReconcileDiffItem) {
    const target = parseReconcileBizKey(row.bizKey, row.checkDimension)
    const flowLine = target.flowId ? [h('div', { class: 'sub-text' }, `流水 ${target.flowId}`)] : []
    if (target.kind === 'order') {
      return h('div', [
        h(
          ElButton,
          {
            type: 'primary',
            link: true,
            class: 'link-cell',
            onClick: () => goOrder(target.orderNo!)
          },
          () => target.orderNo
        ),
        h('div', { class: 'sub-text' }, '订单')
      ])
    }
    if (target.kind === 'card') {
      return h('div', [h('div', `水卡 ${target.cardId}`), ...flowLine])
    }
    if (target.kind === 'income') {
      return h('div', [h('div', `收益账户 用户${target.userId}`), ...flowLine])
    }
    return h('div', [h('div', row.bizKey || '—'), h('div', { class: 'sub-text' }, '对象待核实')])
  }

  /** 订单类差异直达订单查询：订单号是全链路共键，落到订单页即可继续追溯与处理。 */
  const goOrder = (orderNo: string) => {
    router.push({ path: '/order/index', query: { orderNo } })
  }

  /**
   * 把差异台账切到指定账期，只保留账期一个条件（replaceSearchParams 同时回到第一页）。
   * 被点的「差异数」是该账期全部分类的总数，残留一个差异分类筛选，落地列表就只剩其中一类——
   * 按钮上写 7、表里出 2，运营会当成这个账期只有 2 处账实不符。
   */
  const focusDiffs = (bizDate: string) => {
    diffForm.value = { bizDate }
    diffReplace({ ...diffForm.value })
    diffGetData()
  }

  const handleRun = async () => {
    const date = (runDate.value || '').trim()
    if (!/^\d{8}$/.test(date)) {
      ElMessage.warning('请先选择账期')
      return
    }
    running.value = true
    try {
      const task = await fetchReconcileRun(date)
      ElMessage.success(`对账完成：核对 ${task.checkTotal} 项，差异 ${task.diffTotal} 项`)
      taskGetData()
      focusDiffs(date)
    } finally {
      running.value = false
    }
  }

  const diffSearchItems = computed(() => [
    {
      label: '账期',
      key: 'bizDate',
      type: 'date',
      placeholder: '账期',
      clearable: true,
      valueFormat: 'YYYYMMDD'
    },
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

  :deep(.sub-text) {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  :deep(.link-cell) {
    height: auto;
    padding: 0;
  }
</style>
