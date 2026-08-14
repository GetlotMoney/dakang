<!--
  售后台账：挂在订单中心页内（不新增一级菜单/路由），四条来源汇到同一份返还内核，
  每一列都来自服务端；页面不做金额/水量计算，只做「分→元、毫升→升」展示换算。
  高风险操作三道闸（动作权限 → 行状态可执行性 → 逐字重敲售后号）都只是前置提示，
  真正准入判定在后端（类型闸 + 状态 CAS + 共键复核 + 累计封顶）。
-->
<template>
  <div class="after-sale-ledger">
    <!-- 无查询权限时不请求也不留空表：后端同样会 403，此处如实说明而不是渲染一张空台账 -->
    <ElAlert
      v-if="!canQuery"
      type="warning"
      :closable="false"
      show-icon
      title="当前账号没有售后台账查询权限"
    />

    <template v-else>
      <div class="mb-3 flex flex-wrap items-center gap-3">
        <ElInput
          v-model="searchForm.keyword"
          placeholder="售后号 / 订单号 / 用户姓名 / 手机号"
          clearable
          style="width: 260px"
          @input="applyFiltersDebounced"
        />
        <ElSelect
          v-model="searchForm.sourceType"
          placeholder="售后来源"
          clearable
          style="width: 150px"
          @change="applyFilters"
        >
          <ElOption
            v-for="opt in sourceTypeOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </ElSelect>
        <ElSelect
          v-model="searchForm.actionType"
          placeholder="动作类型"
          clearable
          style="width: 150px"
          @change="applyFilters"
        >
          <ElOption
            v-for="opt in actionTypeOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </ElSelect>
        <ElSelect
          v-model="searchForm.actionStatus"
          placeholder="执行状态"
          clearable
          style="width: 150px"
          @change="applyFilters"
        >
          <ElOption
            v-for="opt in actionStatusOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </ElSelect>
      </div>

      <ElTable :data="list" row-key="id" border v-loading="loading">
        <ElTableColumn label="售后号 / 来源" min-width="200" fixed="left">
          <template #default="{ row }">
            <div class="font-medium">{{ row.afterSaleNo || '-' }}</div>
            <ElTag size="small" effect="plain" class="mt-1">
              {{ sourceTypeLabel(row.sourceType) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="关联订单" min-width="180">
          <template #default="{ row }">
            <div>{{ row.orderNo || '-' }}</div>
            <div class="text-xs text-secondary">{{ orderStatusLabel(row.orderStatus) }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="用户 / 目标卡" min-width="170">
          <template #default="{ row }">
            <div>
              {{ row.userName || '-'
              }}<template v-if="row.userMaskedPhone">（{{ row.userMaskedPhone }}）</template>
            </div>
            <div class="text-xs text-secondary">{{ row.cardNo || '未关联水卡' }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="动作 / 策略" min-width="180">
          <template #default="{ row }">
            <div>{{ actionTypeLabel(row.actionType) }}</div>
            <div class="text-xs text-secondary">
              {{ afterSaleStrategyLabel(row.strategyCode)
              }}<template v-if="row.approvedCount != null">
                · 批准 {{ row.approvedCount }} 桶</template
              >
            </div>
          </template>
        </ElTableColumn>
        <!-- 四元额度分列：合计相等而分项不同的两笔，运营口径完全不同，不能只给一个总额 -->
        <ElTableColumn label="返还额度" min-width="200">
          <template #default="{ row }">
            <div>
              合计 <span class="amount">{{ fenText(row.refundAmount) }}</span>
            </div>
            <div class="text-xs text-secondary">
              水品 {{ fenText(row.refundProductFen) }} · 配送费
              {{ fenText(row.refundServiceFen) }}
            </div>
            <div v-if="row.refundProductMl" class="text-xs text-secondary">
              水品水量 {{ mlText(row.refundProductMl) }}
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="执行状态" min-width="180">
          <template #default="{ row }">
            <ElTag :type="statusTagType(row.actionStatus)">
              {{ actionStatusLabel(row.actionStatus) }}
            </ElTag>
            <div v-if="row.retryCount" class="text-xs text-secondary mt-1">
              已重试 {{ row.retryCount }} 次<template v-if="row.nextRetryTime">
                · 下次 {{ formatTime(row.nextRetryTime) }}</template
              >
            </div>
            <div v-if="row.lastError" class="text-xs error-text mt-1" :title="row.lastError">
              {{ row.lastError }}
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="批准 / 终态时间" width="165">
          <template #default="{ row }">
            <div>{{ row.approveByName || '-' }}</div>
            <div class="text-xs text-secondary">批准 {{ formatTime(row.approveTime) }}</div>
            <div class="text-xs text-secondary">终态 {{ formatTime(row.finishTime) }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="登记时间" width="150">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <ElButton type="primary" size="small" link @click="showDetail(row)">详情</ElButton>
            <ElButton
              v-if="row.orderId"
              type="primary"
              size="small"
              link
              @click="emit('trace', row.orderId)"
            >
              追溯
            </ElButton>
            <template v-if="entryOf(row)">
              <ElTooltip
                v-if="!canUseEntry(entryOf(row)!)"
                content="当前账号没有该操作权限"
                placement="top"
              >
                <span>
                  <ElButton type="danger" size="small" link disabled>
                    {{ entryOf(row)?.label }}
                  </ElButton>
                </span>
              </ElTooltip>
              <ElTooltip
                v-else-if="!entryOf(row)?.enabled"
                :content="entryOf(row)?.disabledReason"
                placement="top"
              >
                <span>
                  <ElButton type="danger" size="small" link disabled>
                    {{ entryOf(row)?.label }}
                  </ElButton>
                </span>
              </ElTooltip>
              <ElButton
                v-else
                type="danger"
                size="small"
                link
                @click="openExecute(row, entryOf(row)!)"
              >
                {{ entryOf(row)?.label }}
              </ElButton>
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

    <ElDrawer v-model="detailVisible" title="售后动作详情" size="640px" destroy-on-close>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <ElDescriptions :column="2" border label-width="112px">
            <ElDescriptionsItem label="售后号" :span="2">{{
              detail.afterSaleNo || '-'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="售后来源">{{
              sourceTypeLabel(detail.sourceType)
            }}</ElDescriptionsItem>
            <!-- SOURCE_ID 的语义随来源变化：取消/核账是订单ID，申诉是申诉ID。标签直接写清楚，
                 否则运营拿这个数字去查错的表 -->
            <ElDescriptionsItem :label="sourceIdLabel(detail.sourceType)">
              {{ detail.sourceId || '-' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="关联订单">{{ detail.orderNo || '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="订单状态">{{
              orderStatusLabel(detail.orderStatus)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="订单类型">{{
              orderTypeLabel(detail.orderType)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="detail.orderType === 2 ? '支付来源' : '支付方式'">{{
              afterSalePaymentLabel(detail)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="订单金额">{{
              fenText(detail.orderAmount)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="用户">
              {{ detail.userName || '-'
              }}<template v-if="detail.userMaskedPhone">（{{ detail.userMaskedPhone }}）</template>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="返还目标卡">{{ detail.cardNo || '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="动作类型">{{
              actionTypeLabel(detail.actionType)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="补偿策略">{{
              afterSaleStrategyLabel(detail.strategyCode)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="批准数量">
              {{ detail.approvedCount != null ? `${detail.approvedCount} 桶` : '-' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="返还合计">
              <span class="amount">{{ fenText(detail.refundAmount) }}</span>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="水品返还(金额)">{{
              fenText(detail.refundProductFen)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="配送费返还">{{
              fenText(detail.refundServiceFen)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="水品返还(水量)">{{
              mlText(detail.refundProductMl)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="执行状态">
              <ElTag size="small" :type="statusTagType(detail.actionStatus)">
                {{ actionStatusLabel(detail.actionStatus) }}
              </ElTag>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="乐观锁版本">{{ detail.version ?? '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="重试次数">{{ detail.retryCount ?? '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="下次可重试">{{
              formatTime(detail.nextRetryTime)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="批准人">{{
              detail.approveByName || (detail.approveBy ? `员工#${detail.approveBy}` : '-')
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="批准时间">{{
              formatTime(detail.approveTime)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="终态时间">{{
              formatTime(detail.finishTime)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="登记时间">{{
              formatTime(detail.createTime)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem v-if="detail.lastError" label="最近失败原因" :span="2">
              <span class="error-text">{{ detail.lastError }}</span>
            </ElDescriptionsItem>
          </ElDescriptions>

          <div class="mt-4 flex flex-wrap gap-2">
            <ElButton v-if="detail.orderId" type="primary" @click="emit('trace', detail.orderId)">
              进入订单全链路追溯
            </ElButton>
            <ElButton
              v-if="detailEntry && canUseEntry(detailEntry) && detailEntry.enabled"
              type="danger"
              @click="openExecute(detail, detailEntry)"
            >
              {{ detailEntry.label }}
            </ElButton>
          </div>
        </template>
      </div>
    </ElDrawer>

    <AfterSaleExecuteDialog
      v-model:visible="executeVisible"
      :action="executeTarget"
      :mode="executeMode"
      :action-type-label="actionTypeLabel"
      :action-status-label="actionStatusLabel"
      @done="handleExecuted"
    />
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import {
    afterSaleExecuteEntry,
    afterSaleStrategyLabel,
    AfterSaleActionStatus,
    AfterSalePerms,
    AfterSaleSourceType,
    canUseAfterSaleExecuteEntry,
    fetchAfterSaleActionDetail,
    fetchAfterSaleActionPage,
    type AfterSaleActionItem,
    type AfterSaleExecuteEntry,
    type AfterSaleExecuteMode
  } from '@/api/after-sale'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { fenToYuan, mlToLiter } from '@/utils/format'
  import { DictTypeEnum } from '@/constants/dict'
  import { useUserStore } from '@/store/modules/user'
  import AfterSaleExecuteDialog from './after-sale-execute-dialog.vue'

  interface Props {
    /** 初始关键字（支持从申诉处理/追溯抽屉带订单号或售后号直达）。 */
    initialKeyword?: string
    /**
     * 初始执行状态(1372)。订单中心顶部的售后待办每格按某个执行状态精确计数，
     * 点开必须落到同一个条件上，否则点开的是全状态台账，那个数字在列表里找不到出处。
     */
    initialActionStatus?: number
    /** 面板是否处于激活视图；非激活时不发请求，避免订单中心切页签就打一次售后接口。 */
    active?: boolean
  }

  const props = withDefaults(defineProps<Props>(), { initialKeyword: '', active: true })
  const emit = defineEmits<{
    /** 请求父级打开该订单的全链路追溯抽屉。 */
    (e: 'trace', orderId: string): void
    /** 关键字变化，父级据此同步地址栏，保证深链可回放。 */
    (e: 'keyword-change', keyword: string): void
    /** 执行状态筛选变化，父级据此同步地址栏与顶部待办的选中态。 */
    (e: 'action-status-change', actionStatus?: number): void
  }>()

  const userStore = useUserStore()
  const hasPermission = (permission: string): boolean =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === permission)
  const canQuery = computed(() => hasPermission(AfterSalePerms.query))

  const loading = ref(false)
  const list = ref<AfterSaleActionItem[]>([])
  const total = ref(0)
  const pageParams = reactive({ current: 1, size: 20 })
  const searchForm = reactive<{
    keyword: string
    sourceType?: number
    actionType?: number
    actionStatus?: number
  }>({
    keyword: props.initialKeyword,
    sourceType: undefined,
    actionType: undefined,
    actionStatus: props.initialActionStatus
  })

  const detailVisible = ref(false)
  const detailLoading = ref(false)
  const detail = ref<AfterSaleActionItem | null>(null)

  const executeVisible = ref(false)
  const executeMode = ref<AfterSaleExecuteMode>('execute')
  const executeTarget = ref<AfterSaleActionItem | null>(null)

  // 枚举一律走字典（1340/1341/1346/1370/1371/1372），页面不硬编码下拉选项
  const orderTypeOptions = ref<{ label: string; value: number }[]>([])
  const orderStatusOptions = ref<{ label: string; value: number }[]>([])
  const payWayOptions = ref<{ label: string; value: number }[]>([])
  const sourceTypeOptions = ref<{ label: string; value: number }[]>([])
  const actionTypeOptions = ref<{ label: string; value: number }[]>([])
  const actionStatusOptions = ref<{ label: string; value: number }[]>([])

  /**
   * 懒加载：订单中心默认停在订单查询视图，本面板此时不发任何请求（字典 6 次 + 台账 1 次）。
   * 首次切到售后台账才初始化，之后常驻不再重复拉取。
   */
  let initialized = false
  /**
   * 非激活期间收到的筛选变更（父级切视图时会把地址栏里的售后参数清空）只改了条件没重查，
   * 回到本视图时必须补一次：否则筛选控件显示的是新条件、表里还是上一批数据。
   */
  let pendingReload = false

  async function ensureInitialized() {
    if (!props.active) return
    if (initialized) {
      if (!pendingReload) return
      pendingReload = false
      await loadData()
      return
    }
    initialized = true
    try {
      const [orderTypes, orderStatuses, payWays, sources, actionTypes, actionStatuses] =
        await Promise.all([
          fetchDictOptions(DictTypeEnum.订单类型),
          fetchDictOptions(DictTypeEnum.订单状态),
          fetchDictOptions(DictTypeEnum.支付方式),
          fetchDictOptions(DictTypeEnum.售后来源),
          fetchDictOptions(DictTypeEnum.售后动作类型),
          fetchDictOptions(DictTypeEnum.售后执行状态)
        ])
      orderTypeOptions.value = toDictOptions(orderTypes)
      orderStatusOptions.value = toDictOptions(orderStatuses)
      payWayOptions.value = toDictOptions(payWays)
      sourceTypeOptions.value = toDictOptions(sources)
      actionTypeOptions.value = toDictOptions(actionTypes)
      actionStatusOptions.value = toDictOptions(actionStatuses)
    } catch {
      // 字典接口不可用时保持空选项：宁可筛选不可用，也不在前端硬编码第二份枚举。
    }
    await loadData()
  }

  onMounted(ensureInitialized)
  watch(() => props.active, ensureInitialized)

  watch(
    () => props.initialKeyword,
    (keyword) => {
      if (keyword === searchForm.keyword) return
      searchForm.keyword = keyword
      pageParams.current = 1
      if (!props.active) {
        pendingReload = true
        return
      }
      // 尚未初始化时由 ensureInitialized 带着新关键字首次加载，避免连打两次台账接口
      if (!initialized) {
        ensureInitialized()
        return
      }
      loadData()
    }
  )

  /**
   * 外部指定执行状态（顶部待办下钻）：只保留这一个条件。
   * 计数就是按单个执行状态查出来的，台账再留着来源/动作类型的旧筛选，落地列表与那个数字就不同源了；
   * 本页自己改状态时值已同步（下方 emit），此处比对相等即直接返回，不会误清用户手上的筛选。
   */
  watch(
    () => props.initialActionStatus,
    (actionStatus) => {
      if (actionStatus === searchForm.actionStatus) return
      searchForm.actionStatus = actionStatus
      searchForm.sourceType = undefined
      searchForm.actionType = undefined
      pageParams.current = 1
      if (!props.active) {
        pendingReload = true
        return
      }
      if (!initialized) {
        ensureInitialized()
        return
      }
      loadData()
    }
  )

  const dictLabel = (options: { label: string; value: number }[], value?: number) =>
    options.find((item) => item.value === value)?.label || (value == null ? '-' : String(value))

  const orderTypeLabel = (value?: number) => dictLabel(orderTypeOptions.value, value)
  const orderStatusLabel = (value?: number) => dictLabel(orderStatusOptions.value, value)
  const payWayLabel = (value?: number) => dictLabel(payWayOptions.value, value)
  const paySourceLabel = (value?: number) =>
    value === 1 ? '微信支付' : value === 2 ? '模拟支付' : '来源缺失'
  const afterSalePaymentLabel = (row: AfterSaleActionItem) =>
    row.orderType === 2 ? paySourceLabel(row.paySource) : payWayLabel(row.payWay)
  const sourceTypeLabel = (value?: number) => dictLabel(sourceTypeOptions.value, value)
  const actionTypeLabel = (value?: number) => dictLabel(actionTypeOptions.value, value)
  const actionStatusLabel = (value?: number) => dictLabel(actionStatusOptions.value, value)

  /** SOURCE_ID 指向哪张表由来源决定，标签必须跟着变。 */
  const sourceIdLabel = (sourceType?: number) =>
    sourceType === AfterSaleSourceType.DELIVERY_APPEAL ? '来源申诉ID' : '来源订单ID'

  const fenText = (fen?: number) => (fen == null ? '-' : `￥${fenToYuan(fen)}`)
  const mlText = (ml?: number) => (ml == null ? '-' : mlToLiter(ml))

  const statusTagType = (value?: number) =>
    value === AfterSaleActionStatus.SUCCESS
      ? 'success'
      : value === AfterSaleActionStatus.RECONCILIATION_REQUIRED ||
          value === AfterSaleActionStatus.TERMINATED
        ? 'danger'
        : value === AfterSaleActionStatus.RETRY_WAIT
          ? 'warning'
          : value === AfterSaleActionStatus.PROCESSING
            ? 'primary'
            : 'info'

  const formatTime = (time?: string) => {
    if (!time || time.length !== 14) return time || '-'
    return `${time.slice(0, 4)}-${time.slice(4, 6)}-${time.slice(6, 8)} ${time.slice(8, 10)}:${time.slice(10, 12)}`
  }

  /** 执行入口的分流口径统一在 @/api/after-sale.ts，申诉页共用同一份，避免两处分叉。 */
  const entryOf = (row: AfterSaleActionItem) => afterSaleExecuteEntry(row)
  const canUseEntry = (entry: AfterSaleExecuteEntry) =>
    canUseAfterSaleExecuteEntry(entry, hasPermission)

  const detailEntry = computed(() => (detail.value ? entryOf(detail.value) : null))

  /**
   * 请求代际：一次下钻会同时改关键字与执行状态两个入参，两条链可能同时在飞；
   * 不比代际的话，先发的那次后回包就会把上一组筛选的结果留在表里，而筛选控件显示的是新条件。
   */
  let loadSequence = 0

  async function loadData() {
    if (!canQuery.value) return
    const sequence = ++loadSequence
    loading.value = true
    try {
      const result = await fetchAfterSaleActionPage({
        current: pageParams.current,
        size: pageParams.size,
        keyword: searchForm.keyword || undefined,
        sourceType: searchForm.sourceType,
        actionType: searchForm.actionType,
        actionStatus: searchForm.actionStatus
      })
      if (sequence !== loadSequence) return
      list.value = result.list
      total.value = result.total
    } finally {
      if (sequence === loadSequence) loading.value = false
    }
  }

  async function applyFilters() {
    pageParams.current = 1
    emit('keyword-change', searchForm.keyword)
    emit('action-status-change', searchForm.actionStatus)
    await loadData()
  }

  const applyFiltersDebounced = useDebounceFn(() => applyFilters(), 350)

  async function showDetail(row: AfterSaleActionItem) {
    detail.value = null
    detailVisible.value = true
    detailLoading.value = true
    try {
      detail.value = await fetchAfterSaleActionDetail(row.id)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载售后详情失败')
    } finally {
      detailLoading.value = false
    }
  }

  function openExecute(row: AfterSaleActionItem, entry: AfterSaleExecuteEntry) {
    if (!canUseEntry(entry)) {
      ElMessage.warning('当前账号没有该操作权限')
      return
    }
    executeTarget.value = row
    executeMode.value = entry.mode
    executeVisible.value = true
  }

  /** 执行只是被受理：终态一律回表读，绝不在前端把行状态改成「已完成」。 */
  async function handleExecuted() {
    detailVisible.value = false
    await loadData()
  }

  /**
   * 供父级在外部写操作（如取水核账确认会登记一条来源=取水异常核账的售后动作）之后刷新台账。
   * 尚未初始化时什么都不做：首次切到本视图时本就会完整加载一次。
   */
  async function reload() {
    if (!initialized) return
    await loadData()
  }

  defineExpose({ reload })
</script>

<style scoped>
  .text-secondary {
    color: var(--el-text-color-secondary);
  }

  .error-text {
    color: var(--el-color-danger);
    word-break: break-all;
  }

  .amount {
    font-weight: 600;
  }
</style>
