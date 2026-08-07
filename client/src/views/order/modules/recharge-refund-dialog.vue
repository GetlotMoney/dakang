<!--
  充值/购卡退款（E2E-04 包D-5，REQ-061，售后来源 4充值退款）。

  这个弹窗只做两件事：把服务端算好的依据摊开给运营看，然后把「订单ID + 受理说明」提交回去。
  它<b>不参与任何金额计算</b>——可退金额按冻结公式从权益批次折算，冲减量取批次剩余，
  卡是否注销由 CardClosureRule 判定，全部由 /refund/recharge/preview 下发。
  这不是偷懒：前端算一遍就等于系统里有两套资金算法，两者哪天漂移都是资金事故。

  为什么必须先预览再受理：退款一旦受理，钱会原路退回用户支付账户，不可撤销。
  运营点下去之前必须看到三件事——退多少钱、卡上少多少权益、这张卡会不会被注销。

  两个金额是不同维度，界面上刻意分成两块展示：
  · 退回支付账户 = 按实付折算（赠送与已消费部分不退）；
  · 从卡上收回 = 批次剩余（含未消费的赠送）。
  混在一起会让运营误以为「退了 92 元就只收回 92 元的权益」。
-->
<template>
  <ElDialog
    v-model="dialogVisible"
    title="充值退款"
    width="680px"
    align-center
    destroy-on-close
    :close-on-click-modal="false"
  >
    <div v-loading="loading">
      <template v-if="mode === 'unsettled'">
        <ElAlert
          class="mb-3"
          type="error"
          :closable="false"
          show-icon
          title="将按原支付单全额发起原路退款"
          description="发起后不可撤销。"
        />
        <ElDescriptions :column="2" border label-width="120px">
          <ElDescriptionsItem label="充值订单">{{ orderNo || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="订单状态"
            ><ElTag type="warning">异常待补偿</ElTag></ElDescriptionsItem
          >
          <ElDescriptionsItem label="原订单金额" :span="2">
            {{ fenText(orderAmount) }}
          </ElDescriptionsItem>
        </ElDescriptions>
      </template>
      <template v-else-if="preview">
        <ElAlert
          class="mb-3"
          :type="preview.refundable ? 'error' : 'warning'"
          :closable="false"
          show-icon
          :title="
            preview.refundable ? '受理后将向支付渠道发起原路退款，不可撤销' : '当前订单不可退款'
          "
          :description="
            preview.refundable
              ? '退款是否成功以支付渠道的最终结果为准。'
              : preview.blockReason || '暂无可退依据'
          "
        />

        <ElDescriptions :column="2" border label-width="120px">
          <ElDescriptionsItem label="充值订单">{{ preview.orderNo || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="订单状态">
            <ElTag size="small" :type="preview.orderStatus === 4 ? 'success' : 'warning'">
              {{ orderStatusLabel(preview.orderStatus) }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="水卡">{{ preview.cardNo || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="权益来源">
            {{ batchSourceLabel(preview.batchSourceType) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="本笔实付">{{
            fenText(preview.payAmountFen)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="折算方式">
            {{ preview.waterPackage ? '水量套餐（按已用水量占比）' : '纯金额套餐（按已消费金额）' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="发放权益">
            {{ fenText(preview.grantAmountFen) }} / {{ mlText(preview.grantWaterMl) }}
            <span v-if="preview.grantBonusFen" class="ml-2 text-xs text-secondary">
              含赠送 {{ fenText(preview.grantBonusFen) }}（已消费的赠送不退）
            </span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="剩余权益">
            {{ fenText(preview.remainAmountFen) }} / {{ mlText(preview.remainWaterMl) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="preview.waterPackage" label="已消费水量" :span="2">
            {{ mlText(preview.usedWaterMl) }}
            <span class="ml-2 text-xs text-secondary">不足一分的尾差不退</span>
          </ElDescriptionsItem>
        </ElDescriptions>

        <div class="mt-4 grid grid-cols-2 gap-3">
          <div class="rounded border border-solid border-[var(--art-border-color)] p-3">
            <div class="text-xs text-secondary">退回用户支付账户</div>
            <div class="mt-1 text-xl text-danger">{{ fenText(preview.refundableFen) }}</div>
            <div class="mt-1 text-xs text-secondary">按本笔实付折算</div>
          </div>
          <div class="rounded border border-solid border-[var(--art-border-color)] p-3">
            <div class="text-xs text-secondary">从水卡收回权益</div>
            <div class="mt-1 text-xl">
              {{ fenText(preview.reverseFen) }} / {{ mlText(preview.reverseMl) }}
            </div>
            <div class="mt-1 text-xs text-secondary">等于卡上剩余权益，与退款金额不同</div>
          </div>
        </div>

        <ElAlert
          v-if="preview.cardWillClose"
          class="mt-3"
          type="warning"
          :closable="false"
          show-icon
          title="退款完成后这张水卡将转为注销"
        />
      </template>
      <ElEmpty v-else-if="!loading" description="未获取到退款依据" :image-size="56" />
      <template v-if="mode === 'unsettled' || preview">
        <ElForm class="mt-4" label-width="96px">
          <ElFormItem label="受理说明" required>
            <ElInput
              v-model="handleRemark"
              type="textarea"
              :rows="3"
              :maxlength="WATER_REMARK_MAX_LENGTH"
              show-word-limit
              :disabled="mode === 'entitlement' && !preview?.refundable"
              placeholder="请写明退款依据（用户诉求、核对了哪些账本证据、是否已与用户确认）"
            />
          </ElFormItem>
        </ElForm>
      </template>
    </div>

    <template #footer>
      <ElButton @click="dialogVisible = false">取消</ElButton>
      <ElButton type="danger" :disabled="!canSubmit" :loading="submitting" @click="submit">
        受理并发起退款
      </ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    AfterSalePerms,
    fetchRechargeRefundPreview,
    requestRechargeRefund,
    requestUnsettledRechargeRefund,
    WATER_REMARK_MAX_LENGTH,
    type RechargeRefundPreview
  } from '@/api/after-sale'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { fenToYuan, mlToLiter } from '@/utils/format'
  import { DictTypeEnum } from '@/constants/dict'
  import { useUserStore } from '@/store/modules/user'

  interface Props {
    visible: boolean
    orderId?: string
    mode?: 'entitlement' | 'unsettled'
    orderNo?: string
    orderAmount?: number
  }

  const props = defineProps<Props>()
  const mode = computed(() => props.mode ?? 'entitlement')
  const orderNo = computed(() => props.orderNo)
  const orderAmount = computed(() => props.orderAmount)
  const emit = defineEmits<{
    'update:visible': [value: boolean]
    done: []
  }>()

  const userStore = useUserStore()
  /** 财务审核权限：无此权限时后端 403，这里提前拦住并说明原因。 */
  const canRefund = computed(() =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === AfterSalePerms.refund)
  )

  const loading = ref(false)
  const submitting = ref(false)
  const preview = ref<RechargeRefundPreview | null>(null)
  const handleRemark = ref('')
  const orderStatusOptions = ref<{ label: string; value: number }[]>([])
  const batchSourceOptions = ref<{ label: string; value: number }[]>([])

  const dialogVisible = computed({
    get: () => props.visible,
    set: (v: boolean) => emit('update:visible', v)
  })

  /** 提交闸：服务端说可退 + 有权限 + 说明非空。三条缺一不可，最终判定仍在服务端。 */
  const canSubmit = computed(
    () =>
      (mode.value === 'unsettled' || preview.value?.refundable === true) &&
      canRefund.value &&
      handleRemark.value.trim().length > 0
  )

  const fenText = (v?: number) => (v == null ? '-' : `${fenToYuan(v)} 元`)
  const mlText = (v?: number) => (v == null ? '-' : `${mlToLiter(v)} 升`)
  const orderStatusLabel = (v?: number) =>
    v == null ? '-' : orderStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const batchSourceLabel = (v?: number) =>
    v == null ? '-' : batchSourceOptions.value.find((o) => o.value === v)?.label || String(v)

  watch(
    () => props.visible,
    async (visible) => {
      if (!visible) return
      handleRemark.value = ''
      preview.value = null
      if (mode.value === 'unsettled') return
      await Promise.all([loadDicts(), loadPreview()])
    }
  )

  async function loadDicts() {
    if (orderStatusOptions.value.length && batchSourceOptions.value.length) return
    try {
      const [statuses, sources] = await Promise.all([
        fetchDictOptions(DictTypeEnum.订单状态),
        fetchDictOptions(DictTypeEnum.权益批次来源)
      ])
      orderStatusOptions.value = toDictOptions(statuses)
      batchSourceOptions.value = toDictOptions(sources)
    } catch {
      // 字典失败只影响文案可读性，不影响金额与可否提交，故不打断弹窗
    }
  }

  async function loadPreview() {
    if (!props.orderId) return
    loading.value = true
    try {
      preview.value = await fetchRechargeRefundPreview(props.orderId)
    } catch {
      preview.value = null
    } finally {
      loading.value = false
    }
  }

  /**
   * 二次确认里必须重述金额与卡处置：这是最后一次让运营发现「点错了单」的机会。
   * 退款受理之后钱就在退回的路上，没有撤销入口。
   */
  async function submit() {
    if (!canSubmit.value || !props.orderId) return
    const p = preview.value
    try {
      const message =
        mode.value === 'unsettled'
          ? `将按原支付单全额原路退款，此操作不可撤销，确认受理？`
          : `将向支付渠道退款 ${fenText(p?.refundableFen)}，并从水卡 ${p?.cardNo || ''} 收回 ` +
            `${fenText(p?.reverseFen)} / ${mlText(p?.reverseMl)} 权益` +
            `${p?.cardWillClose ? '，该卡随后转为注销' : ''}。此操作不可撤销，确认受理？`
      await ElMessageBox.confirm(message, '确认发起充值退款', {
        type: 'warning',
        confirmButtonText: '确认受理',
        cancelButtonText: '再看看'
      })
    } catch {
      return
    }
    submitting.value = true
    try {
      const requestRefund =
        mode.value === 'unsettled' ? requestUnsettledRechargeRefund : requestRechargeRefund
      await requestRefund({
        orderId: props.orderId,
        handleRemark: handleRemark.value.trim()
      })
      // 只说"已受理"：退款成功与否由服务方事实决定，页面不得替它宣告成功
      ElMessage.success(
        mode.value === 'unsettled' ? '退款已受理，订单状态稍后更新' : '退款已受理，权益冲正稍后完成'
      )
      dialogVisible.value = false
      emit('done')
    } finally {
      submitting.value = false
    }
  }
</script>
