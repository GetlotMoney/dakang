<!-- 商城订单台账（E2E-09 S2，PC 只读）：按订单号/状态/前置仓检索，详情抽屉看快照证据。
     本页刻意没有任何推进状态的按钮——后台改单会绕过库存动作与支付事实，
     让订单、库存、资金三者各说各话；需要人工干预时走对账流程，不走改单。 -->
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
          <BusinessTableSummary :total="pagination.total" :page-size="data.length" unit="单">
            <span class="table-summary-amount">本页金额 ￥{{ pageAmountYuan }}</span>
          </BusinessTableSummary>
        </template>
        <template #right>
          <ArtExcelExport
            :data="orderExportRows"
            :filename="orderExportFilename"
            sheet-name="商城订单"
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

    <MallOrderDetailDrawer v-model:visible="detailVisible" :order-no="detailOrderNo" />
  </div>
</template>

<script setup lang="ts">
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import BusinessTableSummary from '@/components/business/business-table-summary/index.vue'
  import MallOrderDetailDrawer from './modules/mall-order-detail-drawer.vue'
  import { useTable } from '@/hooks/core/useTable'
  import {
    fetchMallOrderPage,
    fetchMallWarehouseList,
    type MallOrderItem,
    type MallWarehouseItem
  } from '@/api/mall'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { fenToYuan } from '@/utils/format'
  import { ElButton, ElTag } from 'element-plus'
  import dayjs from 'dayjs'
  import { useUserStore } from '@/store/modules/user'
  import { currentPageExportFilename, currentPageExportRows } from '@/utils/current-page-export'

  defineOptions({ name: 'MallOrder' })

  const router = useRouter()
  const userStore = useUserStore()
  const canViewUserProfile = computed(() =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === 'user:user:query')
  )

  const warehouses = ref<MallWarehouseItem[]>([])
  const orderStatusOptions = ref<{ label: string; value: number }[]>([])
  const payStatusOptions = ref<{ label: string; value: number }[]>([])

  const searchForm = ref<{ orderNo?: string; orderStatus?: number; warehouseId?: string }>({})

  const searchItems = computed(() => [
    {
      label: '订单号',
      key: 'orderNo',
      type: 'input',
      placeholder: '输入完整订单号',
      clearable: true
    },
    {
      label: '订单状态',
      key: 'orderStatus',
      type: 'select',
      placeholder: '全部状态',
      clearable: true,
      options: orderStatusOptions.value
    },
    {
      label: '前置仓',
      key: 'warehouseId',
      type: 'select',
      placeholder: '全部仓',
      clearable: true,
      options: warehouses.value.map((w) => ({ label: w.warehouseName, value: w.id }))
    }
  ])

  onMounted(async () => {
    const [orderStatuses, payStatuses] = await Promise.all([
      fetchDictOptions(DictTypeEnum.商城订单状态),
      fetchDictOptions(DictTypeEnum.商城支付状态)
    ])
    orderStatusOptions.value = toDictOptions(orderStatuses)
    payStatusOptions.value = toDictOptions(payStatuses)
    warehouses.value = await fetchMallWarehouseList()
  })

  const orderStatusLabel = (v?: number) =>
    orderStatusOptions.value.find((o) => o.value === v)?.label ?? (v == null ? '-' : String(v))

  /** 无支付单是正常状态（下单后支付单未生成/已清理），如实呈现而不是显示成待支付 */
  const payStatusLabel = (v?: number) =>
    v == null
      ? '暂无支付单'
      : (payStatusOptions.value.find((o) => o.value === v)?.label ?? String(v))

  const orderStatusTagType = (v?: number) =>
    v === 4 ? 'success' : v === 5 ? 'info' : v === 6 ? 'danger' : v === 1 ? 'warning' : 'primary'

  const payStatusTagType = (v?: number) =>
    v === 2 ? 'success' : v === 3 ? 'danger' : v === 1 ? 'warning' : 'info'

  const formatTime = (t?: string) =>
    t && t.length === 14 ? dayjs(t, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss') : '-'

  /** 列表摘要：首个商品名 + 商品种类数（明细全量在详情抽屉里看） */
  const productSummary = (row: MallOrderItem) => {
    const name = row.firstProductName || '-'
    const kinds = row.itemKindCount ?? 0
    return kinds > 1 ? `${name} 等 ${kinds} 种` : name
  }

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
      apiFn: fetchMallOrderPage,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'orderNo', label: '订单号', width: 190 },
        {
          prop: 'userId',
          label: '下单用户',
          width: 110,
          formatter: (row: MallOrderItem) =>
            canViewUserProfile.value
              ? h(
                  ElButton,
                  {
                    type: 'primary',
                    link: true,
                    class: 'user-link',
                    onClick: () => goUserProfile(row.userId)
                  },
                  () => row.userId
                )
              : row.userId
        },
        {
          prop: 'firstProductName',
          label: '商品',
          minWidth: 180,
          formatter: (row: MallOrderItem) => productSummary(row)
        },
        {
          prop: 'orderAmountFen',
          label: '订单金额(元)',
          width: 120,
          align: 'right',
          formatter: (row: MallOrderItem) => fenToYuan(row.orderAmountFen)
        },
        {
          prop: 'orderStatus',
          label: '订单状态',
          width: 120,
          align: 'center',
          formatter: (row: MallOrderItem) =>
            h(ElTag, { type: orderStatusTagType(row.orderStatus), size: 'small' }, () =>
              orderStatusLabel(row.orderStatus)
            )
        },
        {
          prop: 'payStatus',
          label: '支付状态',
          width: 110,
          align: 'center',
          formatter: (row: MallOrderItem) =>
            h(
              ElTag,
              { type: payStatusTagType(row.payStatus), size: 'small', effect: 'plain' },
              () => payStatusLabel(row.payStatus)
            )
        },
        { prop: 'warehouseName', label: '履约仓', width: 130 },
        { prop: 'receiverName', label: '收货人', width: 100 },
        { prop: 'maskedPhone', label: '收货电话', width: 130 },
        {
          prop: 'createTime',
          label: '下单时间',
          width: 170,
          formatter: (row: MallOrderItem) => formatTime(row.createTime)
        },
        {
          prop: 'operation',
          label: '操作',
          width: 90,
          fixed: 'right',
          formatter: (row: MallOrderItem) =>
            h(
              ElButton,
              { link: true, type: 'primary', onClick: () => openDetail(row) },
              () => '详情'
            )
        }
      ]
    }
  })

  const pageAmountYuan = computed(() =>
    fenToYuan((data.value as MallOrderItem[]).reduce((sum, row) => sum + row.orderAmountFen, 0))
  )

  const orderExportFilename = currentPageExportFilename('商城订单')
  const orderExportRows = computed(() =>
    currentPageExportRows<MallOrderItem>(data.value as MallOrderItem[], [
      { header: '订单号', value: (row) => row.orderNo },
      { header: '用户ID', value: (row) => row.userId },
      { header: '商品', value: (row) => productSummary(row) },
      { header: '订单金额(元)', value: (row) => fenToYuan(row.orderAmountFen) },
      { header: '订单状态', value: (row) => orderStatusLabel(row.orderStatus) },
      { header: '支付状态', value: (row) => payStatusLabel(row.payStatus) },
      { header: '履约仓', value: (row) => row.warehouseName || '' },
      { header: '收货人', value: (row) => row.receiverName || '' },
      { header: '收货电话', value: (row) => row.maskedPhone || '' },
      { header: '下单时间', value: (row) => formatTime(row.createTime) }
    ])
  )

  const goUserProfile = (userId: string) => {
    router.push({ path: '/user/index', query: { userId } })
  }

  const handleSearch = () => {
    replaceSearchParams({
      ...searchForm.value,
      orderNo: searchForm.value.orderNo?.trim() || undefined
    })
    getData()
  }

  const handleReset = () => {
    searchForm.value = {}
    replaceSearchParams({})
    getData()
  }

  const detailVisible = ref(false)
  const detailOrderNo = ref<string>()

  const openDetail = (row: MallOrderItem) => {
    detailOrderNo.value = row.orderNo
    detailVisible.value = true
  }
</script>

<style scoped>
  .table-summary-amount {
    color: var(--el-text-color-secondary);
  }

  .export-button-content,
  :deep(.user-link) {
    display: inline-flex;
    align-items: center;
  }

  .export-button-content {
    gap: 6px;
  }

  :deep(.user-link) {
    height: auto;
    padding: 0;
  }
</style>
