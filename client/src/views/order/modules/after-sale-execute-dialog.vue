<!--
  售后高风险操作二次确认（执行返还 / 发起退款 / 生成补送共用）。三条硬约束：
  ① 无任何可编辑金额/水量/目标状态输入：四元额度登记时冻结，此处只原样展示核对；
  ② 确认需逐字重敲售后号：afterSaleNo 是共键复核字段，防止过期台账让运营点中另一笔合法行；
  ③ 权限判定只是前置提示，后端 @SaCheckPermission 才是授权依据。
-->
<template>
  <ElDialog
    v-model="dialogVisible"
    :title="modeMeta.title"
    width="620px"
    align-center
    destroy-on-close
    :close-on-click-modal="false"
  >
    <template v-if="action">
      <ElAlert
        class="mb-4"
        :type="modeMeta.alertType"
        :closable="false"
        show-icon
        :title="modeMeta.alertTitle"
        :description="modeMeta.alertDesc"
      />

      <ElDescriptions :column="2" border label-width="104px">
        <ElDescriptionsItem label="售后号" :span="2">{{
          action.afterSaleNo || '-'
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="关联订单">{{ action.orderNo || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="当前状态">
          <ElTag size="small" :type="statusTagType(action.actionStatus)">
            {{ actionStatusLabel(action.actionStatus) }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="动作类型">{{
          actionTypeLabel(action.actionType)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="补偿策略">{{
          afterSaleStrategyLabel(action.strategyCode)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="用户">
          {{ action.userName || '-'
          }}<template v-if="action.userMaskedPhone">（{{ action.userMaskedPhone }}）</template>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="返还目标卡">{{ action.cardNo || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="批准数量">
          {{ action.approvedCount != null ? `${action.approvedCount} 桶` : '-' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="返还合计">
          <span class="amount">{{ fenText(action.refundAmount) }}</span>
        </ElDescriptionsItem>
        <!-- 四元额度分列展示：payWay=2 时水费与配送费都从余额扣，只看合计无法判断这笔退的是
             水品还是服务费，运营对账与累计封顶都会失真。服务端分列下发，页面就分列呈现。 -->
        <ElDescriptionsItem label="水品返还(金额)">{{
          fenText(action.refundProductFen)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="配送费返还">{{
          fenText(action.refundServiceFen)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="水品返还(水量)">{{
          mlText(action.refundProductMl)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="重试次数">{{ action.retryCount ?? '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="action.lastError" label="最近失败原因" :span="2">
          <span class="error-text">{{ action.lastError }}</span>
        </ElDescriptionsItem>
      </ElDescriptions>

      <div class="confirm-block">
        <div class="font-medium">请输入上方售后号以确认执行对象</div>
        <ElInput
          v-model="confirmInput"
          class="mt-2"
          placeholder="请输入售后号"
          clearable
          maxlength="32"
        />
        <div v-if="confirmInput && !confirmMatched" class="mt-1 text-xs error-text">
          与本行售后号不一致，无法执行
        </div>
      </div>
    </template>

    <template #footer>
      <ElButton @click="dialogVisible = false">取消</ElButton>
      <ElButton
        :type="modeMeta.buttonType"
        :disabled="!confirmMatched"
        :loading="submitting"
        @click="submit"
      >
        {{ modeMeta.confirmText }}
      </ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import {
    afterSaleStrategyLabel,
    executeAfterSaleAction,
    generateAfterSaleResend,
    requestAfterSaleRefund,
    type AfterSaleActionItem,
    type AfterSaleExecuteMode
  } from '@/api/after-sale'
  import { fenToYuan, mlToLiter } from '@/utils/format'

  interface Props {
    visible: boolean
    action: AfterSaleActionItem | null
    mode: AfterSaleExecuteMode
    /** 动作类型(1371) 字典标签函数，由父级统一加载字典后下传，避免每个弹窗重复请求。 */
    actionTypeLabel: (value?: number) => string
    /** 执行状态(1372) 字典标签函数。 */
    actionStatusLabel: (value?: number) => string
  }

  const props = defineProps<Props>()
  const emit = defineEmits<{
    (e: 'update:visible', value: boolean): void
    /** 服务端已受理本次操作，父级据此刷新台账；不代表资金已到账。 */
    (e: 'done', mode: AfterSaleExecuteMode, resultId?: string): void
  }>()

  const confirmInput = ref('')
  const submitting = ref(false)

  const dialogVisible = computed({
    get: () => props.visible,
    set: (value) => emit('update:visible', value)
  })

  /**
   * 三种模式的文案分工必须精确到“做完之后发生了什么”：
   * 执行返还立刻动卡内权益；发起退款只是受理，成败由外部回执决定；生成补送不动一分钱，
   * 售后动作要等补送任务被签收才转已完成。把它们写成同一句“确认执行吗”会直接误导运营。
   */
  const MODE_META: Record<
    AfterSaleExecuteMode,
    {
      title: string
      confirmText: string
      alertType: 'warning' | 'error' | 'info'
      buttonType: 'primary' | 'danger' | 'warning'
      alertTitle: string
      alertDesc: string
    }
  > = {
    execute: {
      title: '执行资金返还',
      confirmText: '确认执行返还',
      alertType: 'error',
      buttonType: 'danger',
      alertTitle: '本操作立即把上述额度返还到用户水卡',
      alertDesc: '执行后不可撤销。'
    },
    refund: {
      title: '发起外部退款',
      confirmText: '确认发起退款',
      alertType: 'error',
      buttonType: 'danger',
      alertTitle: '本操作向支付渠道发起退款，不可撤销',
      alertDesc: '退款是否成功以支付渠道的最终结果为准。'
    },
    resend: {
      title: '生成补送子订单',
      confirmText: '确认生成补送',
      alertType: 'warning',
      buttonType: 'warning',
      alertTitle: '本操作生成补送单，不产生资金变动',
      alertDesc: '补送数量为裁决批准的数量。'
    }
  }

  const modeMeta = computed(() => MODE_META[props.mode])

  /** 逐字比对，不做 trim 之外的宽松匹配：共键复核的意义就在于精确。 */
  const confirmMatched = computed(
    () => !!props.action?.afterSaleNo && confirmInput.value.trim() === props.action.afterSaleNo
  )

  const fenText = (fen?: number) => (fen == null ? '-' : `￥${fenToYuan(fen)}`)
  const mlText = (ml?: number) => (ml == null ? '-' : mlToLiter(ml))

  const statusTagType = (value?: number) =>
    value === 3
      ? 'success'
      : value === 5 || value === 6
        ? 'danger'
        : value === 4
          ? 'warning'
          : value === 2
            ? 'primary'
            : 'info'

  watch(
    () => props.visible,
    (visible) => {
      if (visible) confirmInput.value = ''
    }
  )

  async function submit() {
    const action = props.action
    if (!action || !confirmMatched.value) return
    const params = { id: action.id, afterSaleNo: action.afterSaleNo }
    submitting.value = true
    try {
      if (props.mode === 'execute') {
        await executeAfterSaleAction(params)
        ElMessage.success('返还已执行')
        emit('done', 'execute')
      } else if (props.mode === 'refund') {
        const refundId = await requestAfterSaleRefund(params)
        ElMessage.success(refundId ? `退款已受理，退款单 #${refundId}` : '退款已受理')
        emit('done', 'refund', refundId)
      } else {
        const resendOrderId = await generateAfterSaleResend(params)
        ElMessage.success(resendOrderId ? `补送子订单 #${resendOrderId} 已生成` : '补送已生成')
        emit('done', 'resend', resendOrderId)
      }
      dialogVisible.value = false
    } finally {
      submitting.value = false
    }
  }
</script>

<style scoped>
  .confirm-block {
    padding: 12px 14px;
    margin-top: 16px;
    background: var(--el-fill-color-lighter);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .error-text {
    color: var(--el-color-danger);
  }

  .amount {
    font-weight: 600;
  }
</style>
