<!-- 商城售后台账（E2E-09 S4）：按状态/类型/单号检索，抽屉内完成审核、收货、质检与受控执行。
     可见范围由操作员的前置仓归属在服务端决定，本页不传 warehouseId——前端传仓等于把
     数据范围交给调用方。

     待处理队列（S3）：待审核 / 待收货 / 待质检 / 需人工 四条各自独立成入口，队列即状态筛选，
     故筛选区不再重复给状态下拉。徽标与列表同端点、同一组筛选条件（除队列状态外一字不差，
     每页取 1 条只读总数），筛选一改两处同轮重算，也不在前端另做聚合——徽标与列表对不上的
     队列比没有队列更误事。 -->
<template>
  <div class="art-full-height">
    <BusinessModuleNav module-key="mall" />

    <ElCard class="art-table-card mb-3" shadow="never">
      <ElRadioGroup :model-value="activeQueue" @change="handleQueueChange">
        <ElRadioButton v-for="queue in QUEUES" :key="queue.key" :value="queue.key">
          {{ queue.label }}
          <span v-if="counts[queue.key] != null" class="queue-count">{{ counts[queue.key] }}</span>
        </ElRadioButton>
      </ElRadioGroup>
    </ElCard>

    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      auto-search
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="handleRefresh" />

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

    <MallAfterSaleDrawer
      v-model:visible="drawerVisible"
      :after-sale-no="drawerNo"
      @changed="handleRefresh"
    />
  </div>
</template>

<script setup lang="ts">
  import { computed, h, onMounted, reactive, ref, watch } from 'vue'
  import { useRoute, useRouter } from 'vue-router'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import MallAfterSaleDrawer from './modules/mall-aftersale-drawer.vue'
  import { useTable } from '@/hooks/core/useTable'
  import {
    MALL_AFTER_SALE_STATUS,
    fetchMallAfterSalePage,
    type MallAfterSaleItem
  } from '@/api/mall-aftersale'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { fenToYuan } from '@/utils/format'
  import { ElButton, ElTag } from 'element-plus'
  import dayjs from 'dayjs'

  defineOptions({ name: 'MallAfterSale' })

  type QueueKey = 'audit' | 'receive' | 'inspect' | 'manual' | 'all'

  /**
   * 待处理队列：四条都是「等人做事」的状态，全部挂徽标；
   * 「全部」是台账视角，不挂数字。
   */
  const QUEUES: { key: QueueKey; label: string; status?: number; countable: boolean }[] = [
    {
      key: 'audit',
      label: '待审核',
      status: MALL_AFTER_SALE_STATUS.PENDING_AUDIT,
      countable: true
    },
    {
      key: 'receive',
      label: '待收货',
      status: MALL_AFTER_SALE_STATUS.PENDING_RETURN,
      countable: true
    },
    {
      key: 'inspect',
      label: '待质检',
      status: MALL_AFTER_SALE_STATUS.PENDING_INSPECT,
      countable: true
    },
    {
      key: 'manual',
      label: '需人工',
      status: MALL_AFTER_SALE_STATUS.NEED_MANUAL,
      countable: true
    },
    { key: 'all', label: '全部', status: undefined, countable: false }
  ]

  const route = useRoute()
  const router = useRouter()

  const readQueueKey = (raw: unknown): QueueKey => {
    const value = Array.isArray(raw) ? raw[0] : raw
    return QUEUES.some((q) => q.key === value) ? (value as QueueKey) : 'audit'
  }

  const activeQueue = ref<QueueKey>(readQueueKey(route.query.queue))
  const counts = reactive<Partial<Record<QueueKey, number>>>({})
  const typeOptions = ref<{ label: string; value: number }[]>([])

  const currentQueue = computed(
    () => QUEUES.find((q) => q.key === activeQueue.value) ?? QUEUES[QUEUES.length - 1]
  )

  const searchForm = ref<{
    afterSaleNo?: string
    orderNo?: string
    afterSaleType?: number
  }>({})

  const searchItems = computed(() => [
    { label: '售后单号', key: 'afterSaleNo', type: 'input', clearable: true },
    { label: '订单号', key: 'orderNo', type: 'input', clearable: true },
    {
      label: '售后类型',
      key: 'afterSaleType',
      type: 'select',
      placeholder: '全部类型',
      clearable: true,
      options: typeOptions.value
    }
  ])

  onMounted(async () => {
    typeOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.商城售后类型))
    await refreshAll()
  })

  const statusTagType = (v?: number) =>
    v === MALL_AFTER_SALE_STATUS.COMPLETED
      ? 'success'
      : v === MALL_AFTER_SALE_STATUS.NEED_MANUAL
        ? 'danger'
        : v === MALL_AFTER_SALE_STATUS.REJECTED || v === MALL_AFTER_SALE_STATUS.CANCELLED
          ? 'info'
          : 'warning'

  const formatTime = (t?: string) =>
    t && t.length === 14 ? dayjs(t, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss') : '-'

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    handleSizeChange,
    handleCurrentChange
  } = useTable({
    core: {
      apiFn: fetchMallAfterSalePage,
      apiParams: { current: 1, size: 20, afterSaleStatus: currentQueue.value.status },
      // 首屏由 onMounted 统一发起（要先等类型字典），避免 Hook 自动首查再来一次
      immediate: false,
      columnsFactory: () => [
        { prop: 'afterSaleNo', label: '售后单号', width: 190 },
        { prop: 'orderNo', label: '原订单号', width: 190 },
        { prop: 'afterSaleTypeName', label: '类型', width: 150 },
        {
          prop: 'afterSaleStatus',
          label: '售后状态',
          width: 130,
          align: 'center',
          formatter: (row: MallAfterSaleItem) =>
            h(
              ElTag,
              { type: statusTagType(row.afterSaleStatus), size: 'small' },
              () => row.afterSaleStatusName
            )
        },
        {
          prop: 'refundAmountFen',
          label: '应退金额(元)',
          width: 120,
          align: 'right',
          formatter: (row: MallAfterSaleItem) => fenToYuan(row.refundAmountFen)
        },
        {
          prop: 'applyTime',
          label: '申请时间',
          width: 170,
          formatter: (row: MallAfterSaleItem) => formatTime(row.applyTime)
        },
        {
          prop: 'operation',
          label: '操作',
          width: 110,
          fixed: 'right',
          formatter: (row: MallAfterSaleItem) =>
            h(
              ElButton,
              { link: true, type: 'primary', onClick: () => openDetail(row) },
              () => '售后详情'
            )
        }
      ]
    }
  })

  /**
   * 队列状态之外的筛选条件，列表与徽标共用这一个来源。
   * 各写一份就会让同屏出现两个都叫「待审核」的数字，而看数字的人无从知道该信哪个。
   */
  const queryScope = () => ({
    afterSaleType: searchForm.value.afterSaleType,
    afterSaleNo: searchForm.value.afterSaleNo?.trim() || undefined,
    orderNo: searchForm.value.orderNo?.trim() || undefined
  })

  /** 队列筛选恒由当前队列决定，页面筛选项只能在队列内部再收窄。 */
  const applyQuery = () => {
    replaceSearchParams({ afterSaleStatus: currentQueue.value.status, ...queryScope() })
    return getData()
  }

  /**
   * 徽标计数：每条队列用与列表**同一组**筛选条件各查一次，只取总数。
   * 失败就置空，不显示一个猜出来的数字。
   */
  async function loadCounts() {
    const scope = queryScope()
    await Promise.all(
      QUEUES.filter((q) => q.countable).map(async (q) => {
        try {
          const res = await fetchMallAfterSalePage({
            current: 1,
            size: 1,
            afterSaleStatus: q.status,
            ...scope
          })
          counts[q.key] = res.total
        } catch {
          counts[q.key] = undefined
        }
      })
    )
  }

  /** 列表与徽标恒同轮刷新：筛选一改两处一起重算，否则徽标停在上一次筛选的数字上。 */
  const refreshAll = async () => {
    await applyQuery()
    await loadCounts()
  }

  const handleSearch = () => refreshAll()

  const handleReset = () => {
    searchForm.value = {}
    return refreshAll()
  }

  const handleRefresh = () => refreshAll()

  /** 队列切换写进地址栏：刷新与后退回到同一条队列，这也是队列的返回路径。 */
  async function handleQueueChange(value: string | number | boolean | undefined) {
    const target = readQueueKey(value)
    if (target === activeQueue.value) return
    activeQueue.value = target
    const query = { ...route.query, queue: target }
    if (router.resolve({ path: route.path, query }).fullPath !== route.fullPath) {
      await router.replace({ path: route.path, query })
    }
    await applyQuery()
  }

  watch(
    () => route.query.queue,
    (raw) => {
      const target = readQueueKey(raw)
      if (target === activeQueue.value) return
      activeQueue.value = target
      applyQuery()
    }
  )

  const drawerVisible = ref(false)
  const drawerNo = ref('')

  const openDetail = (row: MallAfterSaleItem) => {
    drawerNo.value = row.afterSaleNo
    drawerVisible.value = true
  }
</script>

<style scoped>
  /* 计数徽标的行盒会撑高所在按钮，而无徽标的「全部」随内容缩矮——
     把每个按钮钉成同一高度并垂直居中，徽标不再参与高度计算 */
  :deep(.el-radio-button__inner) {
    display: inline-flex;
    align-items: center;
    height: 36px;
  }

  .queue-count {
    display: inline-block;
    min-width: 20px;
    padding: 0 6px;
    margin-left: 6px;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-color-white);
    text-align: center;
    background-color: var(--el-color-danger);
    border-radius: 9px;
  }
</style>
