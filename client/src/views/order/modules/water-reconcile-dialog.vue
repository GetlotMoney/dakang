<!--
  取水异常核账（E2E-04 包E，售后来源 3取水异常核账）。

  这个弹窗替代的是原来跑在浏览器里的 fetchResolveWaterException：那套代码自行算缺水量、
  自行决定订单落 4已完成 还是 5已取消，是与服务端账本无关的第二份资金算法，已按任务书删除。

  现在的分工：
  · /water/preview 只读下发核账依据（计划/实际水量、已退差金额与水量、来源判别、建议终态、
    可否确认与阻断原因）。页面一列不推导，连“还差多少水”都不在前端算 —— 那正是旧实现出错的地方。
  · /water/confirm 只提交订单ID与运营核账说明；判别与终态由服务端在事务内按账本重算，
    连预览给出的建议终态都不作数，所以本弹窗也不把它做成可选项。

  状态 6异常待补偿 有两条语义相反的来源（A 已退过差只等复核 / B 一分钱没退），在订单列表上
  长得一模一样。sourceVerdict 就是把这条判别摊开给运营看的字段，必须原样展示。
-->
<template>
  <ElDialog
    v-model="dialogVisible"
    title="取水异常核账"
    width="640px"
    align-center
    destroy-on-close
    :close-on-click-modal="false"
  >
    <div v-loading="loading">
      <template v-if="preview">
        <ElAlert
          class="mb-3"
          :type="preview.confirmable ? 'warning' : 'error'"
          :closable="false"
          show-icon
          :title="
            preview.confirmable ? '核账确认不产生资金变动，仅更新订单状态' : '当前订单不可核账'
          "
          :description="preview.confirmable ? '' : preview.blockReason || '暂无可核账依据'"
        />

        <ElDescriptions :column="2" border label-width="112px">
          <ElDescriptionsItem label="订单号">{{ preview.orderNo || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="订单状态">
            <ElTag size="small" :type="preview.orderStatus === 6 ? 'warning' : 'info'">
              {{ orderStatusLabel(preview.orderStatus) }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="支付方式">{{
            payWayLabel(preview.payWay)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="订单金额">{{
            fenText(preview.orderAmount)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="计划水量">{{ mlText(preview.planMl) }}</ElDescriptionsItem>
          <!-- actualMl 为空即从未结算过，是来源 B 的判别依据之一；此处如实呈现「未结算」而不是 0 -->
          <ElDescriptionsItem label="实际水量">
            <span v-if="preview.actualMl != null">{{ mlText(preview.actualMl) }}</span>
            <span v-else class="text-secondary">未结算</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="已退差金额">{{
            fenText(preview.refundedFen)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="已退差水量">{{
            mlText(preview.refundedMl)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="来源判别" :span="2">
            <ElTag size="small" :type="verdictTagType(preview.sourceVerdict)">
              {{ verdictLabel(preview.sourceVerdict) }}
            </ElTag>
            <span class="ml-2">{{ preview.sourceVerdictDesc || '-' }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="建议终态" :span="2">
            <template v-if="preview.suggestTargetStatus != null">
              {{ orderStatusLabel(preview.suggestTargetStatus) }}
            </template>
            <span v-else class="text-secondary">-</span>
          </ElDescriptionsItem>
        </ElDescriptions>

        <ElForm class="mt-4" label-width="88px">
          <ElFormItem label="核账说明" required>
            <ElInput
              v-model="handleRemark"
              type="textarea"
              :rows="4"
              :maxlength="WATER_REMARK_MAX_LENGTH"
              show-word-limit
              :disabled="!canSubmit"
              placeholder="请写明核对了哪些账本证据（原扣款流水、退差流水、指令回执等）以及结论依据"
            />
          </ElFormItem>
        </ElForm>
      </template>
      <ElEmpty v-else-if="!loading" description="未获取到核账依据" :image-size="56" />
    </div>

    <template #footer>
      <ElButton @click="dialogVisible = false">取消</ElButton>
      <ElButton
        type="danger"
        :disabled="!canSubmit || !handleRemark.trim()"
        :loading="submitting"
        @click="submit"
      >
        确认核账并推进终态
      </ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import {
    AfterSalePerms,
    confirmWaterAbnormal,
    fetchWaterAbnormalPreview,
    WATER_REMARK_MAX_LENGTH,
    type WaterAbnormalPreview
  } from '@/api/after-sale'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { fenToYuan, mlToLiter } from '@/utils/format'
  import { DictTypeEnum } from '@/constants/dict'
  import { useUserStore } from '@/store/modules/user'

  interface Props {
    visible: boolean
    orderId?: string
  }

  const props = defineProps<Props>()
  const emit = defineEmits<{
    (e: 'update:visible', value: boolean): void
    /** 核账已确认，父级刷新订单列表与追溯。 */
    (e: 'done'): void
  }>()

  const userStore = useUserStore()
  const hasPermission = (permission: string): boolean =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === permission)

  const loading = ref(false)
  const submitting = ref(false)
  const preview = ref<WaterAbnormalPreview | null>(null)
  const handleRemark = ref('')
  let loadSequence = 0

  const dialogVisible = computed({
    get: () => props.visible,
    set: (value) => emit('update:visible', value)
  })

  /** 双闸：服务端判定可确认 + 本地有处理权限。任一不成立都不给提交入口。 */
  const canSubmit = computed(
    () => preview.value?.confirmable === true && hasPermission(AfterSalePerms.handle)
  )

  const orderStatusOptions = ref<{ label: string; value: number }[]>([])
  const payWayOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    try {
      const [statuses, payWays] = await Promise.all([
        fetchDictOptions(DictTypeEnum.订单状态),
        fetchDictOptions(DictTypeEnum.支付方式)
      ])
      orderStatusOptions.value = toDictOptions(statuses)
      payWayOptions.value = toDictOptions(payWays)
    } catch {
      // 字典不可用时退回展示原始数值，核账依据本身仍然可读。
    }
  })

  const orderStatusLabel = (value?: number) =>
    orderStatusOptions.value.find((item) => item.value === value)?.label ||
    (value == null ? '-' : String(value))
  const payWayLabel = (value?: number) =>
    payWayOptions.value.find((item) => item.value === value)?.label ||
    (value == null ? '-' : String(value))

  const fenText = (fen?: number) => (fen == null ? '-' : `￥${fenToYuan(fen)}`)
  const mlText = (ml?: number) => (ml == null ? '-' : mlToLiter(ml))

  /** 判别码原样映射，未知码不静默归入 A/B —— 那正是要避免的“两条来源当成一件事”。 */
  const verdictLabel = (verdict?: string) =>
    verdict === 'A'
      ? '已退差待复核'
      : verdict === 'B'
        ? '未退差'
        : verdict === 'UNKNOWN'
          ? '证据不足或账本不一致'
          : verdict || '未给出判别'
  const verdictTagType = (verdict?: string) =>
    verdict === 'A' ? 'success' : verdict === 'B' ? 'warning' : 'danger'

  watch(
    () => [props.visible, props.orderId] as const,
    async ([visible, orderId]) => {
      if (!visible || !orderId) return
      const sequence = ++loadSequence
      preview.value = null
      handleRemark.value = ''
      loading.value = true
      try {
        const result = await fetchWaterAbnormalPreview(orderId)
        if (sequence === loadSequence) preview.value = result
      } catch (error) {
        if (sequence === loadSequence) {
          ElMessage.error(error instanceof Error ? error.message : '加载核账依据失败')
        }
      } finally {
        if (sequence === loadSequence) loading.value = false
      }
    }
  )

  async function submit() {
    const orderId = props.orderId
    const remark = handleRemark.value.trim()
    if (!orderId || !canSubmit.value) return
    if (!remark) {
      ElMessage.warning('请填写核账说明')
      return
    }
    submitting.value = true
    try {
      await confirmWaterAbnormal({ orderId, handleRemark: remark })
      ElMessage.success('核账已确认')
      dialogVisible.value = false
      emit('done')
    } finally {
      submitting.value = false
    }
  }
</script>

<style scoped>
  .text-secondary {
    color: var(--el-text-color-secondary);
  }
</style>
