<!-- 批量控制两步确认（E2E-05 包D，任务书 3.5）：
     第一步 preview——后端解析目标并冻结参数，返回数量/摘要/一次性凭据；
     第二步 confirm——回显摘要 + 凭据领取（过期/重复/换人/参数变化后端全部拒绝）。
     预览后修改任何参数即作废当前凭据，必须重新预览。 -->
<template>
  <ElDialog
    v-model="visible"
    title="发起批量控制"
    width="640px"
    align-center
    :close-on-click-modal="false"
    @closed="resetAll"
  >
    <ElSteps :active="step" align-center class="mb-4">
      <ElStep title="设定目标与指令" />
      <ElStep title="核对并确认下发" />
    </ElSteps>

    <!-- 第一步 -->
    <ElForm v-show="step === 0" label-width="92px">
      <ElFormItem label="控制范围" required>
        <ElRadioGroup v-model="form.scopeType" @change="invalidateTicket">
          <ElRadio :value="1">指定设备</ElRadio>
          <ElRadio :value="2">指定水站</ElRadio>
          <ElRadio :value="3">全部设备</ElRadio>
        </ElRadioGroup>
      </ElFormItem>
      <ElFormItem v-if="form.scopeType === 1" label="目标设备" required>
        <ElSelect
          v-model="form.deviceIds"
          multiple
          filterable
          placeholder="选择设备"
          style="width: 100%"
          :loading="deviceLoading"
          @change="invalidateTicket"
        >
          <ElOption
            v-for="item in devices"
            :key="item.id"
            :label="`${item.deviceName}（${item.deviceNo}）`"
            :value="item.id"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem v-if="form.scopeType === 2" label="目标水站" required>
        <ElSelect
          v-model="form.stationId"
          filterable
          placeholder="选择水站"
          style="width: 100%"
          :loading="stationLoading"
          @change="invalidateTicket"
        >
          <ElOption
            v-for="item in stations"
            :key="item.id"
            :label="item.stationName"
            :value="item.id"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="指令类型" required>
        <ElSelect v-model="form.cmdType" style="width: 100%" @change="invalidateTicket">
          <ElOption
            v-for="item in batchCmdOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </ElSelect>
        <div v-if="form.cmdType === 2" class="form-hint text-error">
          紧急停止仅支持单台正在出水的设备。
        </div>
        <!--
          原本这里对「价格同步」单独挂一句「暂未对接厂商设备」。两个问题：
          一是它只对 8 说，而实际上厂家 MQTT 协议 V-01~V-11 全部待确认，锁机/解锁/重启/
          参数同步同样到不了真机，单独标注 8 会让运维以为其余指令已经能落到设备上；
          二是真机接入就在眼前，接上那天这句立刻变成假话，而假话方向恰好最危险——
          运维会以为价格同步是空操作，实则真改真机价格。
          设备侧到底通没通，看的是设备在线状态与指令回执，不靠这里的静态标注。
        -->
      </ElFormItem>
      <ElFormItem v-if="needPayload" label="参数报文" required>
        <ElInput
          v-model="form.cmdPayload"
          type="textarea"
          :rows="3"
          placeholder='合法 JSON，如 {"priceVersion":"PV-20260730-01"}'
          @input="invalidateTicket"
        />
      </ElFormItem>
    </ElForm>

    <!-- 第二步：预览结果核对 -->
    <div v-show="step === 1">
      <ElAlert
        class="mb-3"
        type="warning"
        :closable="false"
        show-icon
        title="高风险操作核对"
        :description="`请在 ${preview?.ticketTtlSeconds ?? 120} 秒内确认，超时需重新预览。`"
      />
      <ElDescriptions v-if="preview" :column="1" border>
        <ElDescriptionsItem label="指令类型">
          <ElTag type="danger" effect="plain">{{ preview.cmdTypeDesc }}</ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="目标数量">
          <span class="target-count">{{ preview.targetCount }}</span> 台
        </ElDescriptionsItem>
        <ElDescriptionsItem label="目标预览">
          <div class="device-nos">
            <ElTag v-for="no in preview.deviceNos" :key="no" size="small">{{ no }}</ElTag>
            <span v-if="preview.targetCount > preview.deviceNos.length" class="more-hint">
              等 {{ preview.targetCount }} 台
            </span>
          </div>
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="preview.activeOrderNo" label="活动订单">
          {{ preview.activeOrderNo }}
        </ElDescriptionsItem>
      </ElDescriptions>
    </div>

    <template #footer>
      <template v-if="step === 0">
        <ElButton @click="visible = false">取消</ElButton>
        <ElButton type="primary" :loading="previewing" @click="doPreview">预览目标</ElButton>
      </template>
      <template v-else>
        <ElButton @click="step = 0">返回修改</ElButton>
        <ElButton type="danger" :loading="confirming" @click="doConfirm">确认下发</ElButton>
      </template>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import {
    fetchBatchConfirm,
    fetchBatchPreview,
    fetchDevicePage,
    type BatchPreviewResult,
    type DeviceItem
  } from '@/api/device'
  import { fetchStationList, type StationItem } from '@/api/station'

  defineOptions({ name: 'BatchExecuteDialog' })

  const emit = defineEmits<{ executed: [] }>()
  const visible = defineModel<boolean>({ required: true })

  const step = ref(0)
  const previewing = ref(false)
  const confirming = ref(false)
  const preview = ref<BatchPreviewResult | null>(null)

  const devices = ref<DeviceItem[]>([])
  const deviceLoading = ref(false)
  const stations = ref<StationItem[]>([])
  const stationLoading = ref(false)

  const form = reactive({
    scopeType: 1,
    deviceIds: [] as number[],
    stationId: undefined as number | undefined,
    cmdType: 3,
    cmdPayload: ''
  })

  /** 批量白名单 + 紧急停止；开始出水永不出现在这里（订单链路专属） */
  const batchCmdOptions = [
    { label: '查询状态', value: 3 },
    { label: '锁机', value: 4 },
    { label: '解锁', value: 5 },
    { label: '参数同步', value: 6 },
    { label: '重启', value: 7 },
    { label: '价格同步', value: 8 },
    { label: '紧急停止（单设备）', value: 2 }
  ]

  const needPayload = computed(() => form.cmdType === 6 || form.cmdType === 8)

  watch(visible, async (open) => {
    if (!open) return
    if (!devices.value.length) {
      deviceLoading.value = true
      try {
        devices.value = (await fetchDevicePage({ current: 1, size: 100 })).list ?? []
      } finally {
        deviceLoading.value = false
      }
    }
    if (!stations.value.length) {
      stationLoading.value = true
      try {
        stations.value = await fetchStationList()
      } finally {
        stationLoading.value = false
      }
    }
  })

  /** 预览后任何参数变化都作废当前凭据：与后端「参数变化拒绝」保持同一姿态 */
  function invalidateTicket() {
    preview.value = null
    if (step.value === 1) step.value = 0
  }

  async function doPreview() {
    if (form.scopeType === 1 && !form.deviceIds.length) {
      ElMessage.warning('请选择目标设备')
      return
    }
    if (form.scopeType === 2 && !form.stationId) {
      ElMessage.warning('请选择目标水站')
      return
    }
    if (needPayload.value && !form.cmdPayload.trim()) {
      ElMessage.warning('该指令必须携带 JSON 参数')
      return
    }
    previewing.value = true
    try {
      preview.value = await fetchBatchPreview({
        scopeType: form.scopeType,
        deviceIds: form.scopeType === 1 ? form.deviceIds : undefined,
        stationId: form.scopeType === 2 ? form.stationId : undefined,
        cmdType: form.cmdType,
        cmdPayload: form.cmdPayload.trim() || undefined
      })
      step.value = 1
    } finally {
      previewing.value = false
    }
  }

  async function doConfirm() {
    if (!preview.value) return
    confirming.value = true
    try {
      const batchId = await fetchBatchConfirm({
        operationTicket: preview.value.operationTicket,
        targetDigest: preview.value.targetDigest,
        paramDigest: preview.value.paramDigest
      })
      ElMessage.success(`批量任务已创建（批次 ${batchId}）`)
      visible.value = false
      emit('executed')
    } catch {
      // 凭据被拒（过期/重复/换人/参数变化）：回到第一步重新预览
      preview.value = null
      step.value = 0
    } finally {
      confirming.value = false
    }
  }

  function resetAll() {
    step.value = 0
    preview.value = null
    form.scopeType = 1
    form.deviceIds = []
    form.stationId = undefined
    form.cmdType = 3
    form.cmdPayload = ''
  }
</script>

<style scoped lang="scss">
  .form-hint {
    margin-top: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    line-height: 1.5;
  }

  .text-error {
    color: var(--el-color-danger);
  }

  .target-count {
    font-size: 18px;
    font-weight: 600;
    color: var(--el-color-danger);
  }

  .device-nos {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    align-items: center;
  }

  .more-hint {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
</style>
