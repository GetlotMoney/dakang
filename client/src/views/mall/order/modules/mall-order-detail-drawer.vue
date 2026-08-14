<!-- 商城订单详情抽屉（E2E-09 S2，PC 只读）：订单概要 / 收货快照 / 选仓结果 / 支付信息 / 明细快照
     刻意不放任何推进状态的按钮：后台改单会绕过库存动作与支付事实，让订单、库存、资金三者各说各话。
     明细列全部取自下单时刻冻结的快照，商品改名改价后历史订单仍显示成交当时的值。 -->
<template>
  <ElDrawer v-model="drawerVisible" title="订单详情" size="820px" destroy-on-close>
    <div v-loading="loading">
      <template v-if="detail">
        <div class="section-title">订单概要</div>
        <ElDescriptions :column="2" border label-width="96px">
          <ElDescriptionsItem label="订单号" :span="2">
            {{ detail.summary.orderNo }}
            <ElTag class="ml-2" size="small" :type="orderStatusTagType(detail.summary.orderStatus)">
              {{ orderStatusLabel(detail.summary.orderStatus) }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="下单用户">{{ detail.summary.userId }}</ElDescriptionsItem>
          <ElDescriptionsItem label="下单时间">
            {{ formatTime(detail.summary.createTime) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="商品金额">
            ￥{{ fenToYuan(detail.summary.productAmountFen) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="配送费">
            ￥{{ fenToYuan(detail.summary.deliveryFeeFen) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="订单总额">
            <span class="amount-strong">￥{{ fenToYuan(detail.summary.orderAmountFen) }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="商品种类">
            {{ detail.summary.itemKindCount ?? '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="支付截止">
            {{ formatTime(detail.summary.payExpireTime) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="取消时间">
            {{ formatTime(detail.summary.cancelTime) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="detail.cancelReason" label="取消原因" :span="2">
            {{ detail.cancelReason }}
          </ElDescriptionsItem>
        </ElDescriptions>

        <div class="section-title">收货信息</div>
        <ElDescriptions :column="2" border label-width="96px">
          <ElDescriptionsItem label="收货人">
            {{ detail.summary.receiverName || '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="收货电话">
            {{ detail.summary.maskedPhone || '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="收货地区">{{
            detail.receiverRegion || '-'
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="区县编码">
            {{ detail.receiverDistrictCode || '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="详细地址" :span="2">
            {{ detail.receiverAddress || '-' }}
          </ElDescriptionsItem>
        </ElDescriptions>

        <div class="section-title">履约前置仓</div>
        <ElDescriptions :column="2" border label-width="96px">
          <ElDescriptionsItem label="前置仓" :span="2">
            <template v-if="detail.summary.warehouseName">
              {{ detail.summary.warehouseName }}
            </template>
            <ElTag v-else type="info" size="small" effect="plain">未记录</ElTag>
          </ElDescriptionsItem>
        </ElDescriptions>

        <div class="section-title">支付信息</div>
        <ElDescriptions :column="2" border label-width="96px">
          <ElDescriptionsItem label="支付状态">
            <ElTag size="small" :type="payStatusTagType(detail.summary.payStatus)">
              {{ payStatusLabel(detail.summary.payStatus) }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="支付来源">
            {{ paySourceLabel(detail.paySource) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="交易号" :span="2">
            {{ detail.transactionId || '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="支付成功时间" :span="2">
            {{ formatTime(detail.paySuccessTime) }}
          </ElDescriptionsItem>
        </ElDescriptions>

        <div class="section-title">商品明细</div>
        <ElTable v-if="detail.items.length" :data="detail.items" border size="small">
          <ElTableColumn
            prop="productName"
            label="商品名称"
            min-width="150"
            show-overflow-tooltip
          />
          <ElTableColumn prop="skuName" label="规格名称" min-width="120" show-overflow-tooltip />
          <ElTableColumn label="规格" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ specsText(row.specs) }}</template>
          </ElTableColumn>
          <ElTableColumn label="单价(元)" width="100" align="right">
            <template #default="{ row }">{{ fenToYuan(row.unitPriceFen) }}</template>
          </ElTableColumn>
          <ElTableColumn prop="quantity" label="数量" width="70" align="right" />
          <ElTableColumn label="行金额(元)" width="110" align="right">
            <template #default="{ row }">{{ fenToYuan(row.itemAmountFen) }}</template>
          </ElTableColumn>
          <ElTableColumn prop="weightGram" label="重量(克)" width="100" align="right" />
        </ElTable>
        <ElEmpty v-else description="暂无商品明细" :image-size="50" />
      </template>
      <ElEmpty v-else-if="!loading" :description="emptyText" :image-size="56" />
    </div>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { fetchMallOrderDetail, type MallOrderDetail } from '@/api/mall'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { fenToYuan } from '@/utils/format'
  import dayjs from 'dayjs'

  defineOptions({ name: 'MallOrderDetailDrawer' })

  interface Props {
    visible: boolean
    orderNo?: string
  }

  const props = defineProps<Props>()
  const emit = defineEmits<{ (e: 'update:visible', value: boolean): void }>()

  const drawerVisible = computed({
    get: () => props.visible,
    set: (value) => emit('update:visible', value)
  })

  const loading = ref(false)
  const detail = ref<MallOrderDetail | null>(null)
  const emptyText = ref('暂无订单数据')
  // 竞态守卫：快速切换订单时，旧请求的回包不得覆盖新订单的详情
  let loadSequence = 0

  const orderStatusOptions = ref<{ label: string; value: number }[]>([])
  const payStatusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [orderStatuses, payStatuses] = await Promise.all([
      fetchDictOptions(DictTypeEnum.商城订单状态),
      fetchDictOptions(DictTypeEnum.商城支付状态)
    ])
    orderStatusOptions.value = toDictOptions(orderStatuses)
    payStatusOptions.value = toDictOptions(payStatuses)
  })

  const orderStatusLabel = (v?: number) =>
    orderStatusOptions.value.find((o) => o.value === v)?.label ?? (v == null ? '-' : String(v))

  const payStatusLabel = (v?: number) =>
    v == null
      ? '暂无支付单'
      : (payStatusOptions.value.find((o) => o.value === v)?.label ?? String(v))

  const orderStatusTagType = (v?: number) =>
    v === 4 ? 'success' : v === 5 ? 'info' : v === 6 ? 'danger' : v === 1 ? 'warning' : 'primary'

  const payStatusTagType = (v?: number) =>
    v === 2 ? 'success' : v === 3 ? 'danger' : v === 4 ? 'info' : v === 1 ? 'warning' : 'info'

  const paySourceLabel = (v?: number) =>
    v === 1 ? '微信支付' : v === 2 ? '模拟支付' : v == null ? '-' : String(v)

  const specsText = (specs?: Record<string, string>) => {
    const entries = Object.entries(specs || {})
    return entries.length ? entries.map(([k, v]) => `${k}：${v}`).join('，') : '-'
  }

  const formatTime = (t?: string) =>
    t && t.length === 14 ? dayjs(t, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss') : '-'

  watch(
    () => [props.visible, props.orderNo] as const,
    async ([visible, orderNo]) => {
      if (!visible || !orderNo) return

      const currentSequence = ++loadSequence
      detail.value = null
      emptyText.value = '暂无订单数据'
      loading.value = true
      try {
        const result = await fetchMallOrderDetail(orderNo)
        if (currentSequence === loadSequence) detail.value = result
      } catch (error) {
        if (currentSequence === loadSequence) {
          emptyText.value = error instanceof Error ? error.message : '订单详情加载失败'
        }
      } finally {
        if (currentSequence === loadSequence) loading.value = false
      }
    }
  )
</script>

<style scoped>
  .section-title {
    display: flex;
    align-items: center;
    margin: 18px 0 10px;
    font-size: 14px;
    font-weight: 600;
  }

  .section-title:first-child {
    margin-top: 0;
  }

  .section-title::before {
    width: 3px;
    height: 14px;
    margin-right: 8px;
    content: '';
    background: var(--el-color-primary);
    border-radius: 2px;
  }

  .amount-strong {
    font-weight: 600;
    color: var(--el-color-danger);
  }
</style>
