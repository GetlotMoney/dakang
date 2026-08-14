<!-- 异常待补偿队列：从订单查询里分出来的独立入口。
     队列本身就是「订单状态=异常待补偿」，所以筛选区刻意没有状态项——
     同一维度在队列和筛选里各有一份，运营改了筛选就会看到一条自相矛盾的"异常队列"。
     列集也换过：状态列恒定无信息量，让位给判断异常必看的计划→实际水量与站点设备。
     本面板只发起动作请求，终态一律回表读；页面不推进任何订单状态。 -->
<template>
  <div>
    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      auto-search
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="reload">
        <template #left>
          <span class="font-semibold">异常待补偿 {{ pagination.total }} 单</span>
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
        <template #userName="{ row }">
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

        <template #stationName="{ row }">
          <template v-if="row.stationName">
            {{ row.stationName }}
            <div v-if="row.deviceNo" class="text-xs text-g-500"
              >{{ row.deviceNo }} · {{ row.outletNo }}号口</div
            >
          </template>
          <template v-else>-</template>
        </template>

        <template #planMl="{ row }">
          <template v-if="row.planMl">
            {{ mlToLiter(row.planMl) }} →
            <span
              :class="
                row.actualMl != null && row.actualMl < row.planMl
                  ? 'font-semibold text-warning'
                  : ''
              "
            >
              {{ row.actualMl != null ? mlToLiter(row.actualMl) : '…' }}
            </span>
          </template>
          <template v-else>-</template>
        </template>

        <template #operation="{ row }">
          <ElButton type="primary" size="small" link @click="emit('trace', row.id)">
            全链路追溯
          </ElButton>
          <template v-if="row.orderType === 1">
            <ElTooltip v-if="!canQueryAfterSale" content="缺少售后台账查询权限" placement="top">
              <span><ElButton type="warning" size="small" link disabled>取水核账</ElButton></span>
            </ElTooltip>
            <ElButton v-else type="warning" size="small" link @click="emit('reconcile', row)">
              取水核账
            </ElButton>
          </template>
          <template v-if="rechargeRefundEntryMode(row)">
            <ElTooltip v-if="!canRefundRecharge" content="缺少充值退款财务审核权限" placement="top">
              <span>
                <ElButton type="danger" size="small" link disabled>异常单原路退款</ElButton>
              </span>
            </ElTooltip>
            <ElButton v-else type="danger" size="small" link @click="emit('refund', row)">
              异常单原路退款
            </ElButton>
          </template>
        </template>
      </ArtTable>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { useTable } from '@/hooks/core/useTable'
  import { fetchOrderPage, type OrderItem } from '@/api/order'
  import { AfterSalePerms, rechargeRefundEntryMode } from '@/api/after-sale-entry'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { fenToYuan, mlToLiter } from '@/utils/format'
  import { useUserStore } from '@/store/modules/user'
  import { ElTag } from 'element-plus'

  /** 订单状态(1341)：6=异常待补偿，本队列的唯一入口条件。 */
  const ORDER_STATUS_ABNORMAL = 6

  const props = withDefaults(defineProps<{ active?: boolean }>(), { active: true })
  const emit = defineEmits<{
    trace: [orderId: string]
    reconcile: [order: OrderItem]
    refund: [order: OrderItem]
  }>()

  const userStore = useUserStore()
  const hasPermission = (permission: string): boolean =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === permission)
  const canQueryAfterSale = computed(() => hasPermission(AfterSalePerms.query))
  const canRefundRecharge = computed(() => hasPermission(AfterSalePerms.refund))

  const orderTypeOptions = ref<{ label: string; value: number }[]>([])
  const searchForm = ref<{ orderNo?: string; userKeyword?: string; orderType?: number }>({})

  const searchItems = computed(() => [
    { label: '订单号', key: 'orderNo', type: 'input', placeholder: '订单号', clearable: true },
    {
      label: '使用人',
      key: 'userKeyword',
      type: 'input',
      placeholder: '姓名或手机号',
      clearable: true
    },
    {
      label: '订单类型',
      key: 'orderType',
      type: 'select',
      placeholder: '全部类型',
      clearable: true,
      options: orderTypeOptions.value
    }
  ])

  const orderTypeLabel = (v: number) =>
    orderTypeOptions.value.find((o) => o.value === v)?.label || String(v)

  const formatTime = (t?: string) => {
    if (!t || t.length !== 14) return t || '-'
    return `${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}`
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
    handleCurrentChange
  } = useTable({
    core: {
      apiFn: fetchOrderPage,
      apiParams: { current: 1, size: 20, orderStatus: ORDER_STATUS_ABNORMAL },
      immediate: false,
      columnsFactory: () => [
        { prop: 'orderNo', label: '订单号', minWidth: 170, fixed: 'left' },
        {
          prop: 'orderType',
          label: '类型',
          width: 95,
          formatter: (row: OrderItem) => orderTypeLabel(row.orderType)
        },
        { prop: 'userName', label: '使用人', minWidth: 150, useSlot: true },
        { prop: 'stationName', label: '站点 / 设备', minWidth: 180, useSlot: true },
        {
          prop: 'orderAmount',
          label: '金额',
          width: 100,
          align: 'right',
          formatter: (row: OrderItem) => `￥${fenToYuan(row.orderAmount)}`
        },
        { prop: 'planMl', label: '水量(计划→实际)', width: 150, useSlot: true },
        {
          prop: 'createTime',
          label: '下单时间',
          width: 150,
          formatter: (row: OrderItem) => formatTime(row.createTime)
        },
        { prop: 'operation', label: '操作', width: 230, fixed: 'right', useSlot: true }
      ]
    }
  })

  const applyQuery = () => {
    replaceSearchParams({
      orderStatus: ORDER_STATUS_ABNORMAL,
      orderType: searchForm.value.orderType,
      orderNo: searchForm.value.orderNo?.trim() || undefined,
      userKeyword: searchForm.value.userKeyword?.trim() || undefined
    })
    return getData()
  }

  const handleSearch = () => applyQuery()

  const handleReset = () => {
    searchForm.value = {}
    applyQuery()
  }

  /** 供父级在核账/退款受理之后回表刷新；未激活时不发请求。 */
  async function reload() {
    if (!props.active) return
    await applyQuery()
  }

  /** 懒加载：订单中心默认停在订单查询视图，切到本队列才首次取数。 */
  let initialized = false

  async function ensureInitialized() {
    if (initialized || !props.active) return
    initialized = true
    try {
      orderTypeOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.订单类型))
    } catch {
      // 字典不可用时保持空选项：宁可类型筛选不可用，也不在前端硬编码第二份枚举
    }
    await applyQuery()
  }

  onMounted(ensureInitialized)
  watch(() => props.active, ensureInitialized)

  defineExpose({ reload })
</script>

<!--
  本组件不定义样式：次级文字用 text-g-500、警示色用 text-warning，都是 @theme 已有的令牌类。
  曾在此 scoped 重定义 .text-secondary/.text-warning——前者与语义色 secondary（青色强调）撞名且
  含义相反，同一个类名在应用里就有了两种颜色；scoped 还会落到子组件根元素上，
  把用同名类做强调的子组件一起染色。
-->
