<!-- 告警详情抽屉（E2E-05 包D）：处置动作收敛在这里——忽略（仅待处理）与转工单（一告警一单，可回看关联工单） -->
<template>
  <ElDrawer v-model="visible" title="告警详情" size="480px" @open="loadDetail">
    <div v-loading="loading">
      <ElDescriptions v-if="alarm.id" :column="1" border>
        <ElDescriptionsItem label="告警ID">{{ alarm.id }}</ElDescriptionsItem>
        <ElDescriptionsItem label="设备">
          {{ alarm.deviceNo || alarm.deviceId }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="类型">{{ typeLabel(alarm.alarmType) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="等级">
          <ElTag :type="levelTagType(alarm.alarmLevel) as any" size="small">
            {{ levelLabel(alarm.alarmLevel) }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="内容">{{ alarm.alarmContent }}</ElDescriptionsItem>
        <ElDescriptionsItem label="来源引用">{{ alarm.sourceRef || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="状态">
          <ElTag :type="statusTagType(alarm.alarmStatus) as any" size="small">
            {{ statusLabel(alarm.alarmStatus) }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="产生时间">{{ formatTime(alarm.createTime) }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="alarm.recoverTime" label="恢复时间">
          {{ formatTime(alarm.recoverTime) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="alarm.handleTime" label="处置时间">
          {{ formatTime(alarm.handleTime) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="alarm.workOrderId" label="关联工单">
          <ElButton link type="primary" @click="goWorkOrder">
            {{ alarm.workOrderNo || `工单 ${alarm.workOrderId}` }}
          </ElButton>
        </ElDescriptionsItem>
      </ElDescriptions>

      <div v-if="alarm.id && canHandle" class="drawer-actions">
        <ElButton
          v-if="alarm.alarmStatus === 1"
          type="warning"
          :loading="acting"
          @click="handleIgnore"
        >
          忽略告警
        </ElButton>
        <ElButton
          v-if="alarm.alarmStatus === 1"
          type="primary"
          :loading="acting"
          @click="handleToWorkOrder"
        >
          转工单
        </ElButton>
      </div>
    </div>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    fetchAlarmDetail,
    fetchAlarmToWorkOrder,
    fetchIgnoreAlarm,
    type AlarmItem
  } from '@/api/device'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'

  defineOptions({ name: 'AlarmDetailDrawer' })

  const props = defineProps<{
    alarmId?: number
    canHandle: boolean
  }>()
  const emit = defineEmits<{ handled: [] }>()
  const visible = defineModel<boolean>({ required: true })

  const router = useRouter()
  const loading = ref(false)
  const acting = ref(false)
  const alarm = ref<Partial<AlarmItem>>({})

  const typeOptions = ref<{ label: string; value: number }[]>([])
  const statusOptions = ref<{ label: string; value: number }[]>([])
  const levelOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [types, statuses, levels] = await Promise.all([
      fetchDictOptions(DictTypeEnum.告警类型),
      fetchDictOptions(DictTypeEnum.告警状态),
      fetchDictOptions(DictTypeEnum.故障等级)
    ])
    typeOptions.value = toDictOptions(types)
    statusOptions.value = toDictOptions(statuses)
    levelOptions.value = toDictOptions(levels)
  })

  const typeLabel = (v?: number) =>
    typeOptions.value.find((o) => o.value === v)?.label || String(v ?? '-')
  const statusLabel = (v?: number) =>
    statusOptions.value.find((o) => o.value === v)?.label || String(v ?? '-')
  const levelLabel = (v?: number) =>
    levelOptions.value.find((o) => o.value === v)?.label || String(v ?? '-')
  const levelTagType = (v?: number) => (v === 3 ? 'danger' : v === 2 ? 'warning' : 'info')
  const statusTagType = (v?: number) =>
    v === 1 ? 'danger' : v === 2 ? 'primary' : v === 4 ? 'success' : 'info'

  async function loadDetail() {
    if (!props.alarmId) return
    loading.value = true
    try {
      alarm.value = await fetchAlarmDetail(props.alarmId)
    } finally {
      loading.value = false
    }
  }

  async function handleIgnore() {
    await ElMessageBox.confirm('确认忽略该告警？', '忽略告警', {
      type: 'warning'
    })
    acting.value = true
    try {
      await fetchIgnoreAlarm(alarm.value.id!)
      ElMessage.success('已忽略')
      await loadDetail()
      emit('handled')
    } finally {
      acting.value = false
    }
  }

  async function handleToWorkOrder() {
    await ElMessageBox.confirm('将为该告警创建维修工单，确认转工单？', '转工单', {
      type: 'warning'
    })
    acting.value = true
    try {
      const workOrderId = await fetchAlarmToWorkOrder(alarm.value.id!)
      ElMessage.success(`已转工单（工单 ${workOrderId}）`)
      await loadDetail()
      emit('handled')
    } finally {
      acting.value = false
    }
  }

  function goWorkOrder() {
    router.push({ path: '/device/workorder', query: { orderNo: alarm.value.workOrderNo } })
  }

  function formatTime(time?: string) {
    if (!time || time.length !== 14) return time || '-'
    return `${time.slice(0, 4)}-${time.slice(4, 6)}-${time.slice(6, 8)} ${time.slice(8, 10)}:${time.slice(10, 12)}`
  }
</script>

<style scoped lang="scss">
  .drawer-actions {
    display: flex;
    gap: 8px;
    justify-content: flex-end;
    margin-top: 16px;
  }
</style>
