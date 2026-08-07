<!-- 批次详情抽屉（E2E-05 包D）：聚合数量 + 逐设备子指令终态；批次号/指令号三端贯穿可追溯 -->
<template>
  <ElDrawer v-model="visible" title="批次详情" size="640px" @open="loadDetail">
    <div v-loading="loading">
      <ElDescriptions v-if="batch.id" :column="2" border>
        <ElDescriptionsItem label="批次号" :span="2">{{ batch.batchNo }}</ElDescriptionsItem>
        <ElDescriptionsItem label="指令类型">{{ cmdTypeLabel(batch.cmdType!) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="聚合状态">
          <ElTag :type="statusTagType(batch.batchStatus!) as any" size="small">
            {{ statusLabel(batch.batchStatus!) }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="总数">{{ batch.totalCount }}</ElDescriptionsItem>
        <ElDescriptionsItem label="成功/失败/超时">
          {{ batch.successCount }} / {{ batch.failCount }} / {{ batch.timeoutCount }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="发起时间">{{ formatTime(batch.createTime) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="完成时间">{{ formatTime(batch.finishTime) }}</ElDescriptionsItem>
        <ElDescriptionsItem
          v-if="batch.cmdPayload && batch.cmdPayload !== '{}'"
          label="参数"
          :span="2"
        >
          <pre class="payload-pre">{{ prettyJson(batch.cmdPayload) }}</pre>
        </ElDescriptionsItem>
      </ElDescriptions>

      <ElDivider content-position="left">子指令明细</ElDivider>
      <ElTable :data="batch.commands ?? []" border size="small" max-height="420">
        <ElTableColumn prop="cmdNo" label="指令号" min-width="180" />
        <ElTableColumn prop="deviceNo" label="设备" min-width="130">
          <template #default="{ row }">{{ row.deviceNo || row.deviceId }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="cmdStatusTagType(row.cmdStatus) as any" size="small">
              {{ cmdStatusLabel(row.cmdStatus) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="失败原因" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.failReason || '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="终态时间" width="150">
          <template #default="{ row }">{{ formatTime(row.finishTime) }}</template>
        </ElTableColumn>
      </ElTable>
    </div>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { fetchBatchDetail, type BatchItem } from '@/api/device'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'

  defineOptions({ name: 'BatchDetailDrawer' })

  const props = defineProps<{ batchId?: number }>()
  const visible = defineModel<boolean>({ required: true })

  const loading = ref(false)
  const batch = ref<Partial<BatchItem>>({})

  const cmdTypeOptions = ref<{ label: string; value: number }[]>([])
  const cmdStatusOptions = ref<{ label: string; value: number }[]>([])
  const batchStatusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [types, statuses, batchStatuses] = await Promise.all([
      fetchDictOptions(DictTypeEnum.指令类型),
      fetchDictOptions(DictTypeEnum.指令状态),
      fetchDictOptions(DictTypeEnum.批次聚合状态)
    ])
    cmdTypeOptions.value = toDictOptions(types)
    cmdStatusOptions.value = toDictOptions(statuses)
    batchStatusOptions.value = toDictOptions(batchStatuses)
  })

  const cmdTypeLabel = (v: number) =>
    cmdTypeOptions.value.find((o) => o.value === v)?.label || String(v)
  const cmdStatusLabel = (v: number) =>
    cmdStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const statusLabel = (v: number) =>
    batchStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const statusTagType = (v: number) =>
    v === 2 ? 'success' : v === 3 ? 'warning' : v === 4 ? 'danger' : 'primary'
  const cmdStatusTagType = (v: number) =>
    v === 4 ? 'success' : v === 5 || v === 6 ? 'danger' : v === 7 ? 'warning' : 'primary'

  async function loadDetail() {
    if (!props.batchId) return
    loading.value = true
    try {
      batch.value = await fetchBatchDetail(props.batchId)
    } finally {
      loading.value = false
    }
  }

  function prettyJson(raw?: string) {
    if (!raw) return '-'
    try {
      return JSON.stringify(JSON.parse(raw), null, 2)
    } catch {
      return raw
    }
  }

  function formatTime(time?: string) {
    if (!time || time.length !== 14) return time || '-'
    return `${time.slice(0, 4)}-${time.slice(4, 6)}-${time.slice(6, 8)} ${time.slice(8, 10)}:${time.slice(10, 12)}`
  }
</script>

<style scoped lang="scss">
  .payload-pre {
    max-height: 160px;
    padding: 8px;
    margin: 0;
    overflow: auto;
    font-size: 12px;
    background: var(--el-fill-color-light);
    border-radius: 4px;
  }
</style>
