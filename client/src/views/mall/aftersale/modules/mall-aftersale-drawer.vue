<!-- 商城售后抽屉（E2E-09 S4）：详情 + 商品行 + 时间线 + 审核/收货/质检/模拟退款。
     这里刻意没有金额输入框，也没有库存终值输入框——应退金额由服务端按原订单不可变明细
     算出，库存由质检结论决定。任何一个可编辑的数字都会让"退多少"取决于谁在操作。 -->
<template>
  <ElDrawer
    :model-value="visible"
    :title="`售后详情 · ${afterSaleNo}`"
    size="660px"
    @update:model-value="emit('update:visible', $event)"
    @open="loadDetail"
  >
    <div v-loading="loading">
      <ElAlert v-if="errorText" type="error" :closable="false" :title="errorText" show-icon />

      <template v-if="detail">
        <ElDescriptions :column="1" border>
          <ElDescriptionsItem label="售后状态">
            <ElTag :type="statusTagType">{{ detail.afterSaleStatusName }}</ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="售后类型">{{ detail.afterSaleTypeName }}</ElDescriptionsItem>
          <ElDescriptionsItem label="原订单号">{{ detail.orderNo }}</ElDescriptionsItem>
          <ElDescriptionsItem label="应退金额(元)">
            {{ fenToYuan(detail.refundAmountFen) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="申请原因">{{ detail.applyReason }}</ElDescriptionsItem>
          <ElDescriptionsItem v-if="detail.inspectResultName" label="质检结论">
            {{ detail.inspectResultName }}｜{{ detail.inspectRemark }}
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="detail.rejectReason" label="驳回原因">
            {{ detail.rejectReason }}
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="detail.refundStatus" label="退款状态">
            {{ refundStatusText }}
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="detail.exchangeOrderNo" label="换货补发单">
            {{ detail.exchangeOrderNo }}
          </ElDescriptionsItem>
        </ElDescriptions>

        <ElDivider>售后商品</ElDivider>
        <ElTable :data="detail.lines || []" size="small" border>
          <ElTableColumn prop="productName" label="商品" min-width="160" />
          <ElTableColumn prop="skuName" label="规格" width="130" />
          <ElTableColumn prop="quantity" label="数量" width="80" align="center" />
          <ElTableColumn label="单价(元)" width="110" align="right">
            <template #default="{ row }">{{ fenToYuan(row.unitPriceFen) }}</template>
          </ElTableColumn>
          <ElTableColumn label="小计(元)" width="110" align="right">
            <template #default="{ row }">{{ fenToYuan(row.itemAmountFen) }}</template>
          </ElTableColumn>
        </ElTable>

        <div class="aftersale-actions">
          <template
            v-if="detail.afterSaleStatus === MALL_AFTER_SALE_STATUS.PENDING_AUDIT && canAct"
          >
            <ElButton type="primary" :loading="acting" @click="handleAudit(true)">
              审核通过
            </ElButton>
            <ElButton :loading="acting" @click="handleAudit(false)">驳回</ElButton>
          </template>
          <ElButton
            v-else-if="detail.afterSaleStatus === MALL_AFTER_SALE_STATUS.PENDING_RETURN && canAct"
            type="primary"
            :loading="acting"
            @click="handleReceive"
          >
            确认收到退货
          </ElButton>
          <ElButton
            v-else-if="detail.afterSaleStatus === MALL_AFTER_SALE_STATUS.PENDING_INSPECT && canAct"
            type="primary"
            :loading="acting"
            @click="inspectVisible = true"
          >
            填写质检结论
          </ElButton>
          <ElButton
            v-else-if="detail.afterSaleStatus === MALL_AFTER_SALE_STATUS.EXCHANGING && canAct"
            :loading="acting"
            @click="handleAbortExchange"
          >
            中止换货补发
          </ElButton>
          <ElButton
            v-else-if="canRefundSim && canAct"
            type="primary"
            :loading="acting"
            @click="handleRefundSim"
          >
            发起模拟退款
          </ElButton>
          <span v-else class="aftersale-hint">{{ actionHint }}</span>
        </div>

        <ElDivider>售后时间线</ElDivider>
        <ElTimeline>
          <ElTimelineItem
            v-for="node in detail.timeline || []"
            :key="node.traceNode"
            :timestamp="formatTime(node.traceTime)"
            placement="top"
          >
            <div class="aftersale-node">{{ node.traceNodeName }}</div>
            <div class="aftersale-node-text">{{ node.traceText }}（{{ node.actorTypeName }}）</div>
          </ElTimelineItem>
        </ElTimeline>
      </template>
    </div>

    <!-- 质检结论决定退款与回库两件事各自是否发生，所以说明必填：
         一个没有原因的结论事后无从复核。 -->
    <ElDialog v-model="inspectVisible" title="退货质检" width="480px">
      <ElRadioGroup v-model="inspectResult" class="aftersale-radio">
        <ElRadio :value="MALL_INSPECT_RESULT.PASS_RESELLABLE"
          >通过，可重新销售（退款并回库）</ElRadio
        >
        <ElRadio :value="MALL_INSPECT_RESULT.PASS_NOT_RESELLABLE"
          >通过，不可重新销售（退款不回库）</ElRadio
        >
        <ElRadio :value="MALL_INSPECT_RESULT.REJECTED">不通过（驳回，不退款不回库）</ElRadio>
      </ElRadioGroup>
      <ElInput
        v-model="inspectRemark"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        placeholder="请填写质检说明"
        class="aftersale-remark"
      />
      <template #footer>
        <ElButton @click="inspectVisible = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="acting"
          :disabled="!inspectRemark.trim()"
          @click="handleInspect"
        >
          提交结论
        </ElButton>
      </template>
    </ElDialog>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { computed, ref } from 'vue'
  import {
    ElAlert,
    ElButton,
    ElDescriptions,
    ElDescriptionsItem,
    ElDialog,
    ElDivider,
    ElDrawer,
    ElInput,
    ElMessage,
    ElMessageBox,
    ElRadio,
    ElRadioGroup,
    ElTable,
    ElTableColumn,
    ElTag,
    ElTimeline,
    ElTimelineItem
  } from 'element-plus'
  import dayjs from 'dayjs'
  import {
    MALL_AFTER_SALE_STATUS,
    MALL_INSPECT_RESULT,
    fetchMallAfterSaleAudit,
    fetchMallAfterSaleAbortExchange,
    fetchMallAfterSaleDetail,
    fetchMallAfterSaleInspect,
    fetchMallAfterSaleReceive,
    fetchMallAfterSaleRefundSim,
    type MallAfterSaleItem
  } from '@/api/mall-aftersale'
  import { fenToYuan } from '@/utils/format'
  import { hasPermission } from '@/utils/permission'

  const props = defineProps<{ visible: boolean; afterSaleNo: string }>()
  const emit = defineEmits<{ 'update:visible': [value: boolean]; changed: [] }>()

  const loading = ref(false)
  const acting = ref(false)
  const errorText = ref('')
  const detail = ref<MallAfterSaleItem | null>(null)
  const inspectVisible = ref(false)
  const inspectResult = ref<number>(MALL_INSPECT_RESULT.PASS_RESELLABLE)
  const inspectRemark = ref('')

  const statusTagType = computed(() => {
    const status = detail.value?.afterSaleStatus ?? 0
    if (status === MALL_AFTER_SALE_STATUS.COMPLETED) return 'success'
    if (status === MALL_AFTER_SALE_STATUS.REJECTED) return 'info'
    if (status === MALL_AFTER_SALE_STATUS.NEED_MANUAL) return 'danger'
    return 'warning'
  })

  /** 退款处理中且退款单仍待退款时才给模拟退款入口。 */
  const canRefundSim = computed(
    () =>
      detail.value?.afterSaleStatus === MALL_AFTER_SALE_STATUS.REFUNDING &&
      detail.value?.refundStatus === 1
  )

  /**
   * 售后动作按风险分三档授权：审核决定这笔退款要不要发生，收货/质检/中止决定退多少与
   * 货回不回库，模拟退款直接造收款事实。按钮显隐只是不摆出点不动的入口，真正的判定在服务端。
   */
  const AFTER_SALE_PERMS = {
    audit: 'mall:aftersale:audit',
    handle: 'mall:aftersale:handle',
    refund: 'mall:aftersale:refund'
  } as const

  /** 当前状态下可执行动作所要求的权限码；该状态无后台动作时为空。 */
  const requiredPerm = computed(() => {
    const status = detail.value?.afterSaleStatus
    if (status === MALL_AFTER_SALE_STATUS.PENDING_AUDIT) return AFTER_SALE_PERMS.audit
    if (
      status === MALL_AFTER_SALE_STATUS.PENDING_RETURN ||
      status === MALL_AFTER_SALE_STATUS.PENDING_INSPECT ||
      status === MALL_AFTER_SALE_STATUS.EXCHANGING
    ) {
      return AFTER_SALE_PERMS.handle
    }
    return canRefundSim.value ? AFTER_SALE_PERMS.refund : ''
  })

  const canAct = computed(() => Boolean(requiredPerm.value) && hasPermission(requiredPerm.value))

  /**
   * 动作区为空时的三种成因必须分开说，混成一句就会与列表队列打架：
   *
   * - 需人工：中止换货补发是它的唯一入口，服务端没有任何以该状态为起点的动作，
   *   所以既不是「没权限」也不是「无需操作」。列表把它挂成红色待办队列，这里若说
   *   「无需后台操作」，运营会照提示离开，队列数字原样留着，下一个人再来一遍。
   * - 有动作但没权限：不能沿用「无需操作」——那会让人以为这单已经处理完了。
   * - 其余状态（已完成/已驳回/用户侧待办等）：后台确实没有动作可做。
   */
  const actionHint = computed(() => {
    if (detail.value?.afterSaleStatus === MALL_AFTER_SALE_STATUS.NEED_MANUAL) {
      return '本单需线下人工处理。'
    }
    return requiredPerm.value ? '当前账号没有该操作权限。' : '当前状态无需后台操作。'
  })

  const refundStatusText = computed(() => {
    const map: Record<number, string> = { 1: '待退款', 2: '退款成功', 3: '退款失败', 4: '已关闭' }
    const status = detail.value?.refundStatus
    return status ? (map[status] ?? String(status)) : '-'
  })

  function formatTime(value?: string) {
    if (!value) return '-'
    const parsed = dayjs(value, 'YYYYMMDDHHmmss')
    return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value
  }

  async function loadDetail() {
    if (!props.afterSaleNo) return
    loading.value = true
    errorText.value = ''
    detail.value = null
    try {
      detail.value = await fetchMallAfterSaleDetail(props.afterSaleNo)
    } catch (error) {
      errorText.value = error instanceof Error ? error.message : '售后详情读取失败'
    } finally {
      loading.value = false
    }
  }

  async function runAction(action: () => Promise<MallAfterSaleItem>, done: string) {
    acting.value = true
    try {
      detail.value = await action()
      ElMessage.success(done)
      emit('changed')
    } finally {
      acting.value = false
    }
  }

  async function handleAudit(approved: boolean) {
    let remark = '同意'
    if (!approved) {
      // 驳回必须有原因：后端也会拒绝空原因，这里先问一次省一次往返
      const input = await ElMessageBox.prompt('请填写驳回原因', '驳回售后', {
        inputPattern: /\S+/,
        inputErrorMessage: '驳回原因不能为空'
      })
      remark = input.value
    }
    await runAction(
      () => fetchMallAfterSaleAudit({ afterSaleNo: props.afterSaleNo, approved, remark }),
      approved ? '已审核通过' : '已驳回'
    )
  }

  const handleReceive = () =>
    runAction(() => fetchMallAfterSaleReceive(props.afterSaleNo), '已确认收到退货')

  async function handleInspect() {
    await runAction(
      () =>
        fetchMallAfterSaleInspect({
          afterSaleNo: props.afterSaleNo,
          inspectResult: inspectResult.value,
          inspectRemark: inspectRemark.value.trim()
        }),
      '质检结论已提交'
    )
    inspectVisible.value = false
    inspectRemark.value = ''
  }

  const handleRefundSim = () =>
    runAction(() => fetchMallAfterSaleRefundSim(props.afterSaleNo), '已发起模拟退款')

  /**
   * 中止换货补发：原因必填。
   *
   * 中止会释放已预占的库存并把单子交回人工，事后只看到一次状态变更是不够的——
   * 没有原因，谁也说不出当时补发为什么送不出去。
   */
  const handleAbortExchange = async () => {
    const input = await ElMessageBox.prompt('请填写中止原因', '中止换货补发', {
      inputPlaceholder: '例如：配送员已停用，补发无法送出',
      inputValidator: (value: string) => (value || '').trim().length > 0 || '中止原因不能为空'
    }).catch(() => null)
    if (!input) {
      return
    }
    await runAction(
      () =>
        fetchMallAfterSaleAbortExchange({
          afterSaleNo: props.afterSaleNo,
          abortReason: String(input.value).trim()
        }),
      '换货补发已中止，售后单已转待人工'
    )
  }
</script>

<style lang="scss" scoped>
  .aftersale-actions {
    display: flex;
    gap: 12px;
    align-items: center;
    margin-top: 16px;
  }

  .aftersale-hint {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .aftersale-radio {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .aftersale-remark {
    margin-top: 16px;
  }

  .aftersale-node {
    font-weight: 500;
  }

  .aftersale-node-text {
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
</style>
