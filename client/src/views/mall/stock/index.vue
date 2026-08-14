<!-- 商城库存管理（E2E-09 S1，R1-P1-1/P2-2 改仓库规范组件）：按仓/分类/SKU 查询；入库/出库/盘点调整；流水抽屉分页追溯 -->
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
          <ElButton v-if="canAdjust" type="primary" @click="openAdjust()" v-ripple>
            库存动作
          </ElButton>
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

    <!-- 库存动作：入库/出库/盘点调整 -->
    <ElDialog v-model="adjustVisible" title="库存动作" width="480px">
      <ElForm ref="adjustFormRef" :model="adjustForm" :rules="adjustRules" label-width="90px">
        <ElFormItem label="前置仓" prop="warehouseId">
          <ElSelect v-model="adjustForm.warehouseId" placeholder="选择仓库" class="w-full">
            <ElOption v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="SKU" prop="skuId">
          <!-- 候选来自独立接口（SKU⋈商品）：新建 SKU 无库存行也可选中做首次入库（R2-P0） -->
          <ElSelect
            v-model="adjustForm.skuId"
            filterable
            remote
            :remote-method="searchSkuCandidates"
            :loading="skuSearching"
            placeholder="输入 SKU 编号/名称搜索"
            class="w-full"
          >
            <ElOption
              v-for="s in skuOptions"
              :key="s.skuId"
              :label="skuOptionLabel(s)"
              :value="s.skuId"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="动作类型" prop="flowType">
          <ElSelect v-model="adjustForm.flowType" placeholder="选择动作" class="w-full">
            <ElOption
              v-for="o in flowTypeOptions"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="数量(件)" prop="quantity">
          <ElInputNumber v-model="adjustForm.quantity" :min="1" :max="999999" />
        </ElFormItem>
        <ElFormItem label="动作原因" prop="reason">
          <ElInput
            v-model="adjustForm.reason"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="adjustVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="adjusting" @click="submitAdjust">执行</ElButton>
      </template>
    </ElDialog>

    <!-- 流水抽屉：真实分页，第 101 条起同样可见（R1-P1-1） -->
    <ElDrawer v-model="flowVisible" title="库存流水" size="720px">
      <ElTable :data="flowRows" border v-loading="flowLoading">
        <ElTableColumn prop="warehouseName" label="前置仓" width="110" />
        <ElTableColumn prop="skuName" label="SKU" width="120" />
        <ElTableColumn label="类型" width="100">
          <template #default="{ row }">{{ flowTypeLabel(row.flowType) }}</template>
        </ElTableColumn>
        <ElTableColumn label="变化" width="80" align="right">
          <template #default="{ row }">
            <span :class="row.availableChange >= 0 ? 'text-green-600' : 'text-red-500'">
              {{ row.availableChange >= 0 ? '+' : '' }}{{ row.availableChange }}
            </span>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="availableAfter" label="动作后可售" width="100" align="right" />
        <ElTableColumn prop="flowReason" label="原因" min-width="140" />
        <ElTableColumn label="时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty v-if="!flowLoading && flowRows.length === 0" description="暂无流水" />
      <div class="mt-3 flex justify-end">
        <ElPagination
          v-model:current-page="flowCurrent"
          :page-size="flowSize"
          :total="flowTotal"
          layout="total, prev, pager, next"
          small
          background
          @current-change="loadFlows"
        />
      </div>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import {
    fetchMallStockPage,
    fetchMallStockAdjust,
    fetchMallStockFlowPage,
    fetchMallSkuCandidates,
    fetchMallWarehouseList,
    fetchMallCategoryList,
    type MallStockItem,
    type MallStockFlowItem,
    type MallSkuCandidateItem,
    type MallWarehouseItem,
    type MallCategoryItem
  } from '@/api/mall'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { hasPermission } from '@/utils/permission'
  import { ElButton, ElMessage, type FormInstance, type FormRules } from 'element-plus'
  import dayjs from 'dayjs'

  defineOptions({ name: 'MallStock' })

  /**
   * 入库/出库/盘点都改的是可售数量，同属库存调整。没有权限就不给入口——
   * 让人选仓、搜 SKU、填数量与原因之后才被拒，填的东西全部作废。
   * 流水是只读追溯，不挂功能点。
   */
  const canAdjust = computed(() => hasPermission('mall:stock:adjust'))

  const warehouses = ref<MallWarehouseItem[]>([])
  const categories = ref<MallCategoryItem[]>([])
  const flowTypeOptions = ref<{ label: string; value: number }[]>([])
  const searchForm = ref<{ warehouseId?: string; categoryId?: string; keyword?: string }>({})

  const searchItems = computed(() => [
    {
      label: '前置仓',
      key: 'warehouseId',
      type: 'select',
      placeholder: '全部仓',
      clearable: true,
      options: warehouses.value.map((w) => ({ label: w.warehouseName, value: w.id }))
    },
    {
      label: '分类',
      key: 'categoryId',
      type: 'select',
      placeholder: '全部分类',
      clearable: true,
      options: categories.value.map((c) => ({ label: c.categoryName, value: c.id }))
    },
    {
      label: 'SKU',
      key: 'keyword',
      type: 'input',
      placeholder: 'SKU 编号或名称',
      clearable: true
    }
  ])

  onMounted(async () => {
    flowTypeOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.商城库存流水类型))
    warehouses.value = await fetchMallWarehouseList()
    categories.value = await fetchMallCategoryList()
  })

  const flowTypeLabel = (v: number) =>
    flowTypeOptions.value.find((o) => o.value === v)?.label ?? String(v)

  const formatTime = (t?: string) =>
    t && t.length === 14 ? dayjs(t, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss') : '—'

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
      apiFn: fetchMallStockPage,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'warehouseName', label: '前置仓', width: 130 },
        { prop: 'productName', label: '商品', minWidth: 150 },
        { prop: 'skuNo', label: 'SKU编号', width: 130 },
        { prop: 'skuName', label: 'SKU名称', minWidth: 130 },
        { prop: 'availableQty', label: '可售(件)', width: 90, align: 'right' },
        { prop: 'reservedQty', label: '预占(件)', width: 90, align: 'right' },
        {
          prop: 'operation',
          label: '操作',
          width: 170,
          fixed: 'right',
          formatter: (row: MallStockItem) =>
            h('div', [
              canAdjust.value &&
                h(
                  ElButton,
                  { link: true, type: 'primary', onClick: () => openAdjust(row) },
                  () => '动作'
                ),
              h(ElButton, { link: true, type: 'info', onClick: () => openFlows(row) }, () => '流水')
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

  /**
   * SKU 候选：独立接口远程搜索（数据源=SKU⋈商品，禁从库存行/列表页派生——
   * 新建 SKU 无库存行会失去首次入库入口，R2-P0）。
   */
  const skuOptions = ref<MallSkuCandidateItem[]>([])
  const skuSearching = ref(false)

  const skuOptionLabel = (s: MallSkuCandidateItem) =>
    `${s.skuNo} ${s.skuName}${s.productName ? `（${s.productName}）` : ''}${
      s.skuStatus === 2 ? '【停用】' : ''
    }`

  const searchSkuCandidates = async (keyword?: string) => {
    skuSearching.value = true
    try {
      const res = await fetchMallSkuCandidates({
        current: 1,
        size: 50,
        keyword: keyword || undefined
      })
      // 已选中项若不在本批结果里，保留其既有选项，避免选中值失去可读标签
      const selected = adjustForm.value.skuId
      const kept = selected ? skuOptions.value.find((s) => s.skuId === selected) : undefined
      skuOptions.value =
        kept && !res.list.some((s) => s.skuId === kept.skuId) ? [kept, ...res.list] : res.list
    } finally {
      skuSearching.value = false
    }
  }

  // ==== 库存动作（requestId 幂等锚：对话框打开期间持有，提交重试不翻倍） ====
  interface AdjustForm {
    warehouseId?: string
    skuId?: string
    flowType?: number
    quantity: number
    reason: string
  }

  const adjustVisible = ref(false)
  const adjusting = ref(false)
  const adjustFormRef = ref<FormInstance>()
  const adjustForm = ref<AdjustForm>({ quantity: 1, reason: '' })
  let adjustRequestId = ''
  const adjustRules: FormRules = {
    warehouseId: [{ required: true, message: '请选择仓库', trigger: 'change' }],
    skuId: [{ required: true, message: '请选择 SKU', trigger: 'change' }],
    flowType: [{ required: true, message: '请选择动作类型', trigger: 'change' }],
    quantity: [{ required: true, message: '请填写数量', trigger: 'blur' }],
    reason: [{ required: true, message: '请填写动作原因', trigger: 'blur' }]
  }

  const openAdjust = (row?: MallStockItem) => {
    adjustRequestId = crypto.randomUUID()
    adjustForm.value = {
      warehouseId: row?.warehouseId,
      skuId: row?.skuId,
      flowType: undefined,
      quantity: 1,
      reason: ''
    }
    // 行内预填时先注入当前行候选（让已选值立即有可读标签），再异步拉默认候选列表
    skuOptions.value = row
      ? [
          {
            skuId: row.skuId,
            skuNo: row.skuNo || row.skuId,
            skuName: row.skuName || '',
            productId: row.productId || '',
            productName: row.productName,
            skuStatus: 1
          }
        ]
      : []
    void searchSkuCandidates()
    adjustVisible.value = true
  }

  const submitAdjust = async () => {
    await adjustFormRef.value?.validate()
    adjusting.value = true
    try {
      // 返回值=原动作冻结结果（重放同源同值）；页面上以刷新列表呈现最新库存
      await fetchMallStockAdjust({
        requestId: adjustRequestId,
        warehouseId: adjustForm.value.warehouseId!,
        skuId: adjustForm.value.skuId!,
        flowType: adjustForm.value.flowType!,
        quantity: adjustForm.value.quantity,
        reason: adjustForm.value.reason.trim()
      })
      ElMessage.success('库存动作已完成')
      adjustVisible.value = false
      refreshData()
    } finally {
      adjusting.value = false
    }
  }

  // ==== 流水抽屉（真实分页消费 total） ====
  const flowVisible = ref(false)
  const flowLoading = ref(false)
  const flowRows = ref<MallStockFlowItem[]>([])
  const flowCurrent = ref(1)
  const flowSize = 20
  const flowTotal = ref(0)
  let flowScope: { warehouseId: string; skuId: string } | null = null

  const loadFlows = async () => {
    if (!flowScope) return
    flowLoading.value = true
    try {
      const res = await fetchMallStockFlowPage({
        current: flowCurrent.value,
        size: flowSize,
        warehouseId: flowScope.warehouseId,
        skuId: flowScope.skuId
      })
      flowRows.value = res.list
      flowTotal.value = res.total
    } finally {
      flowLoading.value = false
    }
  }

  const openFlows = async (row: MallStockItem) => {
    flowScope = { warehouseId: row.warehouseId, skuId: row.skuId }
    flowCurrent.value = 1
    flowTotal.value = 0
    flowRows.value = []
    flowVisible.value = true
    await loadFlows()
  }
</script>
