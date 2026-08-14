<!-- 商城履约（E2E-09 S3-B）：以商城订单为入口，逐单查看履约进度并执行前置仓动作与分配。
     本页复用订单分页接口（履约任务没有独立列表端点——任务由 Worker 按已支付订单幂等补齐，
     订单本身就是它的索引）。

     队列入口（S3）：待履约 / 履约中 / 已完成 三条队列各自独立，队列即状态筛选，
     故筛选区不再重复提供状态下拉（同一维度两处可改会互相打架）。

     徽标口径（两条硬约束，改任一条都要同时改注释）：
     1. 只有「待履约」挂徽标。订单状态「已支付」与履约状态「待拣货」一一对应，是仓内真能
        清空的活；而「履约中」这一个订单状态横跨待打包/待安排发运/待承运方揽收/运输中/
        已送达待确认五个履约节点，后三个由配送员、承运方与用户推进，后台清不动，本页也没有
        按履约节点筛选的能力（缺按履约状态的列表端点）。给它挂红色数字等于承诺了一条清不空
        的待办队列。
     2. 徽标只在筛选收窄到单个前置仓后才给出，且与列表用同一组筛选条件（同端点、除队列状态
        外一字不差，只把每页条数压到 1 取总数）。订单分页接口按全平台返回、不按操作员归属仓
        收口，不选仓时的总数含别人仓的单据，仓管做完自己仓的活它也不会归零。绝不在前端另拼
        一套聚合，否则徽标说 3 条、点进去 5 条。

     签收按钮刻意不在这里：签收是用户的动作，后台代签会让「用户已确认收货」失去意义。 -->
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

    <MallFulfillmentDrawer
      v-model:visible="drawerVisible"
      :order-no="drawerOrderNo"
      @changed="handleRefresh"
    />
  </div>
</template>

<script setup lang="ts">
  import { computed, h, onMounted, reactive, ref, watch } from 'vue'
  import { useRoute, useRouter } from 'vue-router'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import MallFulfillmentDrawer from './modules/mall-fulfillment-drawer.vue'
  import { useTable } from '@/hooks/core/useTable'
  import {
    fetchMallOrderPage,
    fetchMallWarehouseList,
    type MallOrderItem,
    type MallWarehouseItem
  } from '@/api/mall'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { ElButton, ElTag } from 'element-plus'
  import dayjs from 'dayjs'

  defineOptions({ name: 'MallFulfillment' })

  /** 商城订单状态(1393)：履约链只关心已支付之后的三态。 */
  const ORDER_STATUS = { PAID: 2, FULFILLING: 3, FINISHED: 4 } as const

  type QueueKey = 'pending' | 'fulfilling' | 'finished' | 'all'

  /**
   * 队列定义。`countable` 决定是否显示徽标，判据只有一条：这条队列里的单据，仓管在本页
   * 做完动作后数字会不会归零。
   *
   * - 待履约：订单「已支付」⇔ 履约「待拣货」，一单一动作，做完即出队 → 挂徽标。
   * - 履约中：一个订单状态盖住五个履约节点，运输中与已送达待确认要等配送员/承运方/用户，
   *   后台不代签也不代收；页面既没有履约节点列也没有对应筛选 → 不挂徽标。
   * - 已完成 / 全部：台账视角，本就不是待办。
   */
  const QUEUES: { key: QueueKey; label: string; orderStatus?: number; countable: boolean }[] = [
    { key: 'pending', label: '待履约', orderStatus: ORDER_STATUS.PAID, countable: true },
    { key: 'fulfilling', label: '履约中', orderStatus: ORDER_STATUS.FULFILLING, countable: false },
    { key: 'finished', label: '已完成', orderStatus: ORDER_STATUS.FINISHED, countable: false },
    { key: 'all', label: '全部', orderStatus: undefined, countable: false }
  ]

  const route = useRoute()
  const router = useRouter()

  const readQueueKey = (raw: unknown): QueueKey => {
    const value = Array.isArray(raw) ? raw[0] : raw
    return QUEUES.some((q) => q.key === value) ? (value as QueueKey) : 'pending'
  }

  const activeQueue = ref<QueueKey>(readQueueKey(route.query.queue))
  const counts = reactive<Partial<Record<QueueKey, number>>>({})
  const warehouses = ref<MallWarehouseItem[]>([])
  /** 状态文字仍以字典为准：队列名是导航标签，不能拿来当枚举的第二份定义。 */
  const orderStatusOptions = ref<{ label: string; value: number }[]>([])

  const currentQueue = computed(
    () => QUEUES.find((q) => q.key === activeQueue.value) ?? QUEUES[QUEUES.length - 1]
  )

  const searchForm = ref<{ orderNo?: string; warehouseId?: string }>({})

  const searchItems = computed(() => [
    {
      label: '订单号',
      key: 'orderNo',
      type: 'input',
      placeholder: '输入完整订单号',
      clearable: true
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

  const orderStatusTagType = (v?: number) =>
    v === ORDER_STATUS.FINISHED
      ? 'success'
      : v === ORDER_STATUS.FULFILLING
        ? 'warning'
        : v === ORDER_STATUS.PAID
          ? 'primary'
          : 'info'

  const orderStatusLabel = (v?: number) =>
    orderStatusOptions.value.find((o) => o.value === v)?.label ?? (v == null ? '-' : String(v))

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
      apiFn: fetchMallOrderPage,
      apiParams: { current: 1, size: 20, orderStatus: currentQueue.value.orderStatus },
      // 首屏由 onMounted 统一发起（要先等字典与仓列表），避免 Hook 自动首查再来一次
      immediate: false,
      columnsFactory: () => [
        { prop: 'orderNo', label: '订单号', width: 190 },
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
        { prop: 'warehouseName', label: '履约仓', width: 140 },
        { prop: 'receiverName', label: '收货人', width: 100 },
        { prop: 'maskedPhone', label: '收货电话', width: 130 },
        { prop: 'firstProductName', label: '商品', minWidth: 180 },
        {
          prop: 'createTime',
          label: '下单时间',
          width: 170,
          formatter: (row: MallOrderItem) => formatTime(row.createTime)
        },
        {
          prop: 'operation',
          label: '操作',
          width: 110,
          fixed: 'right',
          formatter: (row: MallOrderItem) =>
            h(
              ElButton,
              { link: true, type: 'primary', onClick: () => openFulfillment(row) },
              () => '履约详情'
            )
        }
      ]
    }
  })

  /**
   * 队列状态之外的筛选条件，列表与徽标共用这一个来源。
   * 各写一份是上一版徽标与列表对不上的直接成因，所以这里只允许有一处。
   */
  const queryScope = () => ({
    orderNo: searchForm.value.orderNo?.trim() || undefined,
    warehouseId: searchForm.value.warehouseId
  })

  /** 队列筛选恒由当前队列决定，页面上的筛选项只能在队列内部再收窄。 */
  const applyQuery = () => {
    replaceSearchParams({ orderStatus: currentQueue.value.orderStatus, ...queryScope() })
    return getData()
  }

  /**
   * 徽标计数：逐条队列用与列表**同一组**筛选条件各查一次，只取总数——两处筛选一旦分叉，
   * 同屏就会出现两个都叫「待履约」的数字，而看数字的人无从知道哪个才是自己要做的。
   *
   * 未选前置仓时一律不给数字：订单分页接口不按操作员归属仓收口，全平台总数对仓管
   * 既不是他的活也永远不会归零。任一条查询失败也置空——宁可不显示，也不显示一个
   * 过期或猜出来的数字。
   */
  async function loadCounts() {
    const scope = queryScope()
    for (const queue of QUEUES) {
      counts[queue.key] = undefined
    }
    if (!scope.warehouseId) return
    await Promise.all(
      QUEUES.filter((q) => q.countable).map(async (q) => {
        try {
          const res = await fetchMallOrderPage({
            current: 1,
            size: 1,
            orderStatus: q.orderStatus,
            ...scope
          })
          counts[q.key] = res.total
        } catch {
          counts[q.key] = undefined
        }
      })
    )
  }

  /**
   * 列表与徽标恒同轮刷新：筛选一改，两处必须一起重算。
   * 只刷新列表会让徽标停在上一次筛选的数字上，那正是「两个同名数字对不上」的成因。
   */
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

  /** 队列切换写进地址栏：刷新与浏览器后退都回到同一条队列，这也是队列的返回路径。 */
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

  onMounted(async () => {
    orderStatusOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.商城订单状态))
    warehouses.value = await fetchMallWarehouseList()
    await refreshAll()
  })

  const drawerVisible = ref(false)
  const drawerOrderNo = ref('')

  const openFulfillment = (row: MallOrderItem) => {
    drawerOrderNo.value = row.orderNo
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
