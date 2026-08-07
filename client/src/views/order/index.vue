<!--
  订单中心：取水/充值/配送三类订单 + 全链路追溯（REQ-022/050）+ 售后台账（E2E-04 包E）。

  售后能力以页内视图切换的形式接入，不新增一级菜单、不新增路由：售后动作永远从属于某一笔订单，
  单独开一个顶级入口会让运营在两套列表之间来回对订单号。旧深链（orderNo / orderStatus /
  traceOrderId 等）全部保持原语义，view 参数缺省即订单查询；traceSource=mock 已随 Mock 追溯源退役。
-->
<template>
  <div class="order-page art-full-height">
    <BusinessModuleNav module-key="order" />

    <ElCard class="art-table-card" shadow="never">
      <div class="mb-3">
        <ElRadioGroup :model-value="activeView" @change="handleViewChange">
          <ElRadioButton value="order">订单查询</ElRadioButton>
          <ElRadioButton value="aftersale">售后台账</ElRadioButton>
        </ElRadioGroup>
      </div>

      <AfterSaleLedger
        ref="ledgerRef"
        v-show="activeView === 'aftersale'"
        :active="activeView === 'aftersale'"
        :initial-keyword="afterSaleKeyword"
        @trace="openTraceById"
        @keyword-change="handleAfterSaleKeywordChange"
      />

      <template v-if="activeView === 'order'">
        <!-- 搜索行 -->
        <div class="mb-3 flex flex-wrap items-center gap-3">
          <ElRadioGroup v-model="orderTypeFilter" @change="applyFilters">
            <ElRadioButton value="all">全部类型</ElRadioButton>
            <ElRadioButton value="1">扫码取水</ElRadioButton>
            <ElRadioButton value="2">购卡充值</ElRadioButton>
            <ElRadioButton value="3">水配送订单</ElRadioButton>
          </ElRadioGroup>
          <ElInput
            v-model="searchForm.orderNo"
            placeholder="订单号"
            clearable
            style="width: 220px"
            @input="applyFiltersDebounced"
          />
          <ElInput
            v-model="searchForm.userKeyword"
            placeholder="用户姓名/手机号"
            clearable
            style="width: 180px"
            @input="applyFiltersDebounced"
          />
          <ElSelect
            v-model="searchForm.orderStatus"
            placeholder="订单状态"
            clearable
            style="width: 160px"
            @change="applyFilters"
          >
            <ElOption
              v-for="opt in orderStatusOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </ElSelect>
          <ElButton @click="handleReset" v-ripple>重置</ElButton>
        </div>

        <!-- 订单表格 -->
        <ElTable :data="list" row-key="id" border v-loading="loading">
          <ElTableColumn prop="orderNo" label="订单号" min-width="170" fixed="left" />
          <ElTableColumn label="类型" width="95">
            <template #default="{ row }">
              <ElTag
                :type="
                  row.orderType === 1 ? 'primary' : row.orderType === 2 ? 'success' : 'warning'
                "
              >
                {{ orderTypeLabel(row.orderType) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <!-- 启用 CARD-MEMBER 后，ws_order.USER_ID=实际使用人（可能为授权成员），列名与语义对齐；
             手机号展示服务端脱敏结果，成员用卡时明显标注（持卡人在追溯抽屉呈现） -->
          <ElTableColumn label="使用人" min-width="150">
            <template #default="{ row }">
              {{ row.userName
              }}<template v-if="row.actorMaskedPhone">（{{ row.actorMaskedPhone }}）</template>
              <ElTag
                v-if="row.accessRole === 'MEMBER'"
                type="warning"
                size="small"
                effect="plain"
                class="ml-1"
              >
                成员用卡
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="站点 / 设备" min-width="180">
            <template #default="{ row }">
              <template v-if="row.stationName">
                {{ row.stationName }}
                <div v-if="row.deviceNo" class="text-xs text-secondary"
                  >{{ row.deviceNo }} · {{ row.outletNo }}号口</div
                >
              </template>
              <template v-else>-</template>
            </template>
          </ElTableColumn>
          <ElTableColumn label="金额" width="100" align="right">
            <template #default="{ row }">￥{{ fenToYuan(row.orderAmount) }}</template>
          </ElTableColumn>
          <ElTableColumn label="支付方式" width="120">
            <template #default="{ row }">{{ orderPaymentLabel(row) }}</template>
          </ElTableColumn>
          <ElTableColumn label="水量(计划→实际)" width="140">
            <template #default="{ row }">
              <template v-if="row.planMl">
                {{ mlToLiter(row.planMl) }} →
                <span :class="row.actualMl && row.actualMl < row.planMl ? 'text-warning' : ''">
                  {{ row.actualMl != null ? mlToLiter(row.actualMl) : '…' }}
                </span>
              </template>
              <template v-else>-</template>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" min-width="170">
            <template #default="{ row }">
              <ElTag :type="orderStatusTagType(row.orderStatus)">
                {{ orderStatusLabel(row.orderStatus) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="下单时间" width="150">
            <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <ElButton type="primary" size="small" link @click="showTrace(row)"
                >全链路追溯</ElButton
              >
              <!--
              取水核账（E2E-04 包E）：先读 /water/preview 的只读依据（来源判别、已退差额度、
              可否确认），再由 /water/confirm 在事务内重算终态。原来那套在浏览器里算缺水量、
              自行决定订单终态的 Mock 处理已删除。
            -->
              <template v-if="row.orderType === 1 && row.orderStatus === 6">
                <ElTooltip v-if="!canQueryAfterSale" content="缺少售后台账查询权限" placement="top">
                  <span>
                    <ElButton type="warning" size="small" link disabled>取水核账</ElButton>
                  </span>
                </ElTooltip>
                <ElButton v-else type="warning" size="small" link @click="openWaterReconcile(row)"
                  >取水核账</ElButton
                >
              </template>
              <!--
              充值退款（E2E-04 包D-5）：只对「已完成的充值订单」给入口。
              其余状态的充值单要么没入账（走另一条全额退款路径）、要么已经退过，
              放开入口只会让运营点出一次必然被拒的受理。
              金额一列不在这里算：弹窗读 /refund/recharge/preview 的只读依据。
            -->
              <template v-if="rechargeRefundEntryMode(row)">
                <ElTooltip
                  v-if="!canRefundRecharge"
                  content="缺少充值退款财务审核权限"
                  placement="top"
                >
                  <span>
                    <ElButton type="danger" size="small" link disabled>
                      {{
                        rechargeRefundEntryMode(row) === 'unsettled' ? '异常单原路退款' : '充值退款'
                      }}
                    </ElButton>
                  </span>
                </ElTooltip>
                <ElButton v-else type="danger" size="small" link @click="openRechargeRefund(row)">{{
                  rechargeRefundEntryMode(row) === 'unsettled' ? '异常单原路退款' : '充值退款'
                }}</ElButton>
              </template>
            </template>
          </ElTableColumn>
        </ElTable>

        <div class="mt-3 flex justify-end">
          <ElPagination
            v-model:current-page="pageParams.current"
            v-model:page-size="pageParams.size"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @change="loadData"
          />
        </div>
      </template>

      <!-- 追溯抽屉与核账弹窗被两个视图共用，故放在视图分支之外 -->
      <OrderTraceDrawer v-model:visible="traceVisible" :order-id="currentOrderId" />
      <WaterReconcileDialog
        v-model:visible="reconcileVisible"
        :order-id="reconcileOrderId"
        @done="handleReconcileDone"
      />
      <RechargeRefundDialog
        v-model:visible="rechargeRefundVisible"
        :order-id="rechargeRefundOrderId"
        :mode="rechargeRefundMode"
        :order-no="rechargeRefundOrderNo"
        :order-amount="rechargeRefundOrderAmount"
        @done="handleRechargeRefundDone"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import {
    fetchOrderPage,
    paymentEvidenceStateOf,
    type OrderItem,
    type OrderSearchParams
  } from '@/api/order'
  import { AfterSalePerms } from '@/api/after-sale'
  import { rechargeRefundEntryMode, type RechargeRefundEntryMode } from '@/api/after-sale-entry'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { fenToYuan, mlToLiter } from '@/utils/format'
  import { DictTypeEnum } from '@/constants/dict'
  import OrderTraceDrawer from './modules/order-trace-drawer.vue'
  import AfterSaleLedger from './modules/after-sale-ledger.vue'
  import WaterReconcileDialog from './modules/water-reconcile-dialog.vue'
  import RechargeRefundDialog from './modules/recharge-refund-dialog.vue'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'OrderList' })

  const route = useRoute()
  const router = useRouter()
  const userStore = useUserStore()

  /** 售后查询权限：无此权限时不给核账入口，后端同样 403。 */
  const canQueryAfterSale = computed(() =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === AfterSalePerms.query)
  )

  /**
   * 充值退款走独立财务审核权限（R0-7）：与售后台账的查询/处理权分开。
   * 没有它就不给入口——后端同样 403，提前拦住是为了让运营看到原因而不是一个红条。
   */
  const canRefundRecharge = computed(() =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === AfterSalePerms.refund)
  )

  /** 订单中心的两个页内视图；view 参数缺省即订单查询，旧深链因此完全不受影响。 */
  type OrderCenterView = 'order' | 'aftersale'

  const readQueryValue = (value: unknown): string => {
    const raw = Array.isArray(value) ? value[0] : value
    return typeof raw === 'string' ? raw : ''
  }

  const readPositiveQueryNumber = (value: unknown): number | undefined => {
    const parsed = Number(readQueryValue(value))
    return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
  }

  const queryOrderType = readPositiveQueryNumber(route.query.orderType)
  const orderTypeFilter = ref(
    queryOrderType && [1, 2, 3].includes(queryOrderType) ? String(queryOrderType) : 'all'
  )
  const loading = ref(false)
  const list = ref<OrderItem[]>([])
  const total = ref(0)
  const pageParams = reactive({ current: 1, size: 20 })
  let applyingFilters = false
  const searchForm = reactive({
    orderNo: readQueryValue(route.query.orderNo),
    userKeyword: readQueryValue(route.query.userKeyword),
    // 支持总览待办卡片带参直达（?orderStatus=6 异常待补偿）
    orderStatus: readPositiveQueryNumber(route.query.orderStatus)
  })

  const traceVisible = ref(false)
  const currentOrderId = ref<string>()

  const activeView = ref<OrderCenterView>(route.query.view === 'aftersale' ? 'aftersale' : 'order')
  const afterSaleKeyword = ref(readQueryValue(route.query.afterSaleKeyword))
  const reconcileVisible = ref(false)
  const reconcileOrderId = ref<string>()
  const rechargeRefundVisible = ref(false)
  const rechargeRefundOrderId = ref<string>()
  const rechargeRefundMode = ref<RechargeRefundEntryMode>('entitlement')
  const rechargeRefundOrderNo = ref<string>()
  const rechargeRefundOrderAmount = ref<number>()
  const ledgerRef = ref<InstanceType<typeof AfterSaleLedger> | null>(null)

  // 字典（1340/1341/1346）
  const orderTypeOptions = ref<{ label: string; value: number }[]>([])
  const orderStatusOptions = ref<{ label: string; value: number }[]>([])
  const payWayOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [types, statuses, payWays] = await Promise.all([
      fetchDictOptions(DictTypeEnum.订单类型),
      fetchDictOptions(DictTypeEnum.订单状态),
      fetchDictOptions(DictTypeEnum.支付方式)
    ])
    orderTypeOptions.value = toDictOptions(types)
    orderStatusOptions.value = toDictOptions(statuses)
    payWayOptions.value = toDictOptions(payWays)
    await loadData()
    openTraceFromQuery()
  })

  watch(
    () => route.fullPath,
    () => {
      if (route.path !== '/order/index') return
      activeView.value = route.query.view === 'aftersale' ? 'aftersale' : 'order'
      afterSaleKeyword.value = readQueryValue(route.query.afterSaleKeyword)
      const type = readPositiveQueryNumber(route.query.orderType)
      orderTypeFilter.value = type && [1, 2, 3].includes(type) ? String(type) : 'all'
      searchForm.orderNo = readQueryValue(route.query.orderNo)
      searchForm.userKeyword = readQueryValue(route.query.userKeyword)
      searchForm.orderStatus = readPositiveQueryNumber(route.query.orderStatus)
      pageParams.current = 1
      if (applyingFilters) return
      // 售后视图下不重复拉订单分页；追溯深链两个视图都要能打开
      if (activeView.value !== 'order') {
        openTraceFromQuery()
        return
      }
      loadData().then(openTraceFromQuery)
    }
  )

  const orderTypeLabel = (v: number) =>
    orderTypeOptions.value.find((o) => o.value === v)?.label || String(v)
  const orderStatusLabel = (v: number) =>
    orderStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const payWayLabel = (v: number) =>
    payWayOptions.value.find((o) => o.value === v)?.label || String(v)

  /**
   * 充值单的支付方式必须以该订单的支付记录为准，不能仅凭 payWay=1 推断为微信支付。
   * 非充值订单继续沿用支付方式字典，不改变既有展示口径。
   */
  const orderPaymentLabel = (order: OrderItem): string => {
    if (order.orderType !== 2 || order.payWay !== 1) return payWayLabel(order.payWay)
    const evidenceState = paymentEvidenceStateOf(order)
    if (evidenceState === 'invalid') return '支付记录异常'
    if (evidenceState === 'missing') return '无支付记录'
    if (order.paySource === 2) return '模拟支付'
    if (order.paySource === 1) return '微信支付'
    return '支付来源缺失'
  }

  /** 状态→色调：完成绿 / 进行蓝 / 异常橙 / 退款取消灰红 */
  const orderStatusTagType = (v: number) =>
    v === 4
      ? 'success'
      : v === 6
        ? 'warning'
        : v === 5 || v === 7 || v === 8
          ? 'danger'
          : v === 3
            ? 'primary'
            : 'info'

  const formatTime = (t?: string) => {
    if (!t || t.length !== 14) return t || '-'
    return `${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}`
  }

  async function loadData() {
    loading.value = true
    try {
      const params: OrderSearchParams = {
        current: pageParams.current,
        size: pageParams.size,
        orderType: orderTypeFilter.value === 'all' ? undefined : Number(orderTypeFilter.value),
        orderNo: searchForm.orderNo || undefined,
        userKeyword: searchForm.userKeyword || undefined,
        orderStatus: searchForm.orderStatus
      }
      const res = await fetchOrderPage(params)
      list.value = res.list
      total.value = res.total
    } finally {
      loading.value = false
    }
  }

  async function applyFilters() {
    pageParams.current = 1
    const query = {
      ...(orderTypeFilter.value === 'all' ? {} : { orderType: orderTypeFilter.value }),
      ...(searchForm.orderNo ? { orderNo: searchForm.orderNo } : {}),
      ...(searchForm.userKeyword ? { userKeyword: searchForm.userKeyword } : {}),
      ...(searchForm.orderStatus ? { orderStatus: String(searchForm.orderStatus) } : {})
    }
    const targetFullPath = router.resolve({ path: route.path, query }).fullPath
    if (targetFullPath === route.fullPath) return loadData()
    applyingFilters = true
    try {
      await router.replace({ path: route.path, query })
      await nextTick()
      await loadData()
      openTraceFromQuery()
    } finally {
      applyingFilters = false
    }
  }

  const applyFiltersDebounced = useDebounceFn(() => applyFilters(), 350)

  async function handleReset() {
    searchForm.orderNo = ''
    searchForm.userKeyword = ''
    searchForm.orderStatus = undefined
    orderTypeFilter.value = 'all'
    pageParams.current = 1
    await applyFilters()
  }

  function showTrace(row: OrderItem) {
    currentOrderId.value = row.id
    nextTick(() => {
      traceVisible.value = true
    })
  }

  /**
   * 视图切换写进地址栏，保证刷新与后退回到同一视图。
   * 切到订单查询时把订单筛选条件带回去，切到售后台账时只保留售后关键字，
   * 两组参数互不污染（否则订单号会以关键字身份出现在售后台账的搜索框里）。
   */
  async function handleViewChange(view: string | number | boolean | undefined) {
    const target: OrderCenterView = view === 'aftersale' ? 'aftersale' : 'order'
    activeView.value = target
    const query =
      target === 'aftersale'
        ? {
            view: 'aftersale',
            ...(afterSaleKeyword.value ? { afterSaleKeyword: afterSaleKeyword.value } : {})
          }
        : {
            ...(orderTypeFilter.value === 'all' ? {} : { orderType: orderTypeFilter.value }),
            ...(searchForm.orderNo ? { orderNo: searchForm.orderNo } : {}),
            ...(searchForm.userKeyword ? { userKeyword: searchForm.userKeyword } : {}),
            ...(searchForm.orderStatus ? { orderStatus: String(searchForm.orderStatus) } : {})
          }
    if (router.resolve({ path: route.path, query }).fullPath === route.fullPath) return
    await router.replace({ path: route.path, query })
  }

  function handleAfterSaleKeywordChange(keyword: string) {
    afterSaleKeyword.value = keyword
    if (activeView.value !== 'aftersale') return
    const query = { view: 'aftersale', ...(keyword ? { afterSaleKeyword: keyword } : {}) }
    if (router.resolve({ path: route.path, query }).fullPath === route.fullPath) return
    router.replace({ path: route.path, query })
  }

  /** 取水核账入口：只打开只读预览，是否可确认由服务端 confirmable 决定。 */
  function openWaterReconcile(row: OrderItem) {
    reconcileOrderId.value = row.id
    nextTick(() => {
      reconcileVisible.value = true
    })
  }

  /**
   * 核账确认会推进订单终态，并在售后台账里留下一条来源=取水异常核账的动作。
   * 两个视图都要回表刷新，否则运营在切换回来时看到的是确认之前的旧快照。
   */
  async function handleReconcileDone() {
    await loadData()
    await ledgerRef.value?.reload()
  }

  /** 充值退款入口：只打开只读预览，是否可退由服务端 refundable 决定。 */
  function openRechargeRefund(row: OrderItem) {
    const mode = rechargeRefundEntryMode(row)
    if (!mode) return
    rechargeRefundOrderId.value = row.id
    rechargeRefundMode.value = mode
    rechargeRefundOrderNo.value = row.orderNo
    rechargeRefundOrderAmount.value = row.orderAmount
    nextTick(() => {
      rechargeRefundVisible.value = true
    })
  }

  /**
   * 受理成功只意味着退款单已建立并已向服务方发起：订单终态与卡权益要等事实回签。
   * 两个视图都刷新——台账里会立刻多出一条来源=充值退款的待执行动作，
   * 而订单状态此刻通常还没变，这个"看起来没反应"必须让运营在台账里看到解释。
   */
  async function handleRechargeRefundDone() {
    await loadData()
    await ledgerRef.value?.reload()
  }

  function openTraceById(orderId: string) {
    currentOrderId.value = orderId
    nextTick(() => {
      traceVisible.value = true
    })
  }

  /**
   * 深链打开追溯：不依赖当前分页是否包含该 ID，直接按 traceOrderId 请求追溯，
   * 由抽屉呈现结果或错误。（历史 traceSource=mock 深链已随 Mock 追溯源一并退役，一律走真实接口。）
   */
  function openTraceFromQuery() {
    const raw = route.query.traceOrderId
    const orderId = typeof raw === 'string' && /^\d+$/.test(raw) ? raw : undefined
    if (!orderId) return
    openTraceById(orderId)
  }
</script>

<style scoped>
  .text-secondary {
    color: var(--el-text-color-secondary);
  }

  .text-warning {
    font-weight: 600;
    color: var(--el-color-warning);
  }
</style>
