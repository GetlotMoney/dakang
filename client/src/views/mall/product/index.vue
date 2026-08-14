<!-- 商城商品管理（E2E-09 S1，R1-P1-1/P2-2 改仓库规范组件）：分类筛选、编号/名称搜索、SPU+SKU 联动编辑、上下架、各仓库存查看。
     编辑改为分步弹窗（modules/product-edit-dialog.vue），本页只负责列表、上下架与库存查看。 -->
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
          <ElButton v-if="canEdit" type="primary" @click="openCreate" v-ripple>新增商品</ElButton>
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

    <!-- SPU+SKU 分步编辑 -->
    <ProductEditDialog
      v-model:visible="editVisible"
      :product-id="editingId"
      :categories="categories"
      @saved="refreshData"
    />

    <!-- 各仓库存查看 -->
    <ElDrawer v-model="stockVisible" title="各仓库存" size="520px">
      <ElTable :data="stockRows" border v-loading="stockLoading">
        <ElTableColumn prop="warehouseName" label="前置仓" min-width="130" />
        <ElTableColumn prop="skuName" label="SKU" min-width="130" />
        <ElTableColumn prop="availableQty" label="可售" width="80" align="right" />
        <ElTableColumn prop="reservedQty" label="预占" width="80" align="right" />
      </ElTable>
      <ElEmpty v-if="!stockLoading && stockRows.length === 0" description="暂无库存记录" />
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import ProductEditDialog from './modules/product-edit-dialog.vue'
  import { useTable } from '@/hooks/core/useTable'
  import {
    fetchMallProductPage,
    fetchMallProductDetail,
    fetchMallProductPublish,
    fetchMallProductUnpublish,
    fetchMallCategoryList,
    type MallProductItem,
    type MallCategoryItem,
    type MallStockItem
  } from '@/api/mall'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { hasPermission } from '@/utils/permission'
  import { ElButton, ElMessage, ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'MallProduct' })

  /**
   * 商品维护与上下架是两个独立功能点：改商品资料不等于可以决定它在小程序里卖不卖。
   * 没有权限就不摆出入口——按钮在、点了必被服务端拒，等于让人走完一次确认弹窗
   * 才知道自己不能做这件事。
   */
  const canEdit = computed(() => hasPermission('mall:product:edit'))
  const canShelf = computed(() => hasPermission('mall:product:shelf'))

  const categories = ref<MallCategoryItem[]>([])
  const statusOptions = ref<{ label: string; value: number }[]>([])
  const searchForm = ref<{ categoryId?: string; keyword?: string; status?: number }>({})

  const searchItems = computed(() => [
    {
      label: '分类',
      key: 'categoryId',
      type: 'select',
      placeholder: '全部分类',
      clearable: true,
      options: categories.value.map((c) => ({ label: c.categoryName, value: c.id }))
    },
    {
      label: '编号/名称',
      key: 'keyword',
      type: 'input',
      placeholder: '商品编号或名称',
      clearable: true
    },
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
    statusOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.商城商品状态))
    categories.value = await fetchMallCategoryList()
  })

  const statusLabel = (v: number) =>
    statusOptions.value.find((o) => o.value === v)?.label ?? String(v)
  const statusTag = (v: number) => (v === 2 ? 'success' : v === 3 ? 'info' : 'warning')

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
      apiFn: fetchMallProductPage,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'productNo', label: '商品编号', width: 140 },
        { prop: 'productName', label: '商品名称', minWidth: 180 },
        { prop: 'categoryName', label: '分类', width: 120 },
        { prop: 'skuCount', label: 'SKU数', width: 80, align: 'center' },
        {
          prop: 'productStatus',
          label: '状态',
          width: 100,
          align: 'center',
          formatter: (row: MallProductItem) =>
            h(ElTag, { type: statusTag(row.productStatus) }, () => statusLabel(row.productStatus))
        },
        {
          prop: 'operation',
          label: '操作',
          width: 260,
          fixed: 'right',
          formatter: (row: MallProductItem) =>
            h('div', [
              canEdit.value &&
                h(
                  ElButton,
                  { link: true, type: 'primary', onClick: () => openEdit(row) },
                  () => '编辑'
                ),
              // 各仓库存是只读视图，不挂功能点
              h(
                ElButton,
                { link: true, type: 'info', onClick: () => openStock(row) },
                () => '库存'
              ),
              canShelf.value &&
                (row.productStatus !== 2
                  ? h(
                      ElButton,
                      { link: true, type: 'success', onClick: () => publish(row) },
                      () => '上架'
                    )
                  : h(
                      ElButton,
                      { link: true, type: 'warning', onClick: () => unpublish(row) },
                      () => '下架'
                    ))
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

  // ==== 分步编辑弹窗（详情读取与提交都在弹窗内完成，列表只传定位键） ====
  const editVisible = ref(false)
  const editingId = ref<string>()

  const openCreate = () => {
    editingId.value = undefined
    editVisible.value = true
  }

  const openEdit = (row: MallProductItem) => {
    editingId.value = row.id
    editVisible.value = true
  }

  const publish = async (row: MallProductItem) => {
    await ElMessageBox.confirm(
      '上架需分类启用、存在启用 SKU 且启用仓有可售库存，确认上架？',
      '提示',
      {
        type: 'warning'
      }
    )
    await fetchMallProductPublish({ id: row.id, version: row.version })
    ElMessage.success('已上架')
    refreshData()
  }

  const unpublish = async (row: MallProductItem) => {
    await ElMessageBox.confirm('确认下架该商品？下架不影响已有 SKU 与库存。', '提示', {
      type: 'warning'
    })
    await fetchMallProductUnpublish({ id: row.id, version: row.version })
    ElMessage.success('已下架')
    refreshData()
  }

  // ==== 各仓库存查看 ====
  const stockVisible = ref(false)
  const stockLoading = ref(false)
  const stockRows = ref<MallStockItem[]>([])
  const openStock = async (row: MallProductItem) => {
    stockVisible.value = true
    stockLoading.value = true
    stockRows.value = []
    try {
      const detail = await fetchMallProductDetail(row.id)
      stockRows.value = detail.stocks
    } finally {
      stockLoading.value = false
    }
  }
</script>
