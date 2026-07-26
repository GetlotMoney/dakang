<template>
  <ElDrawer v-model="visible" title="登录日志详情" direction="rtl" size="520px">
    <ElDescriptions :column="1" border>
      <ElDescriptionsItem label="ID">
        {{ detailData.id || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="用户名">
        {{ detailData.logUserName || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="用户类型">
        {{ detailData.logUserType ?? '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="登录类型">
        {{ getLogTypeLabel(detailData.logType) }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="执行时间">
        {{
          detailData.logExecuteTime
            ? dayjs(detailData.logExecuteTime, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss')
            : '-'
        }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="登录IP">
        {{ detailData.logIp || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="User-Agent">
        <span style="word-break: break-all">{{ detailData.logUserAgent || '-' }}</span>
      </ElDescriptionsItem>
      <ElDescriptionsItem label="浏览器">
        {{ detailData.uaBrowser || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="操作系统">
        {{ detailData.uaOs || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="设备平台">
        {{ detailData.uaPlatform || '-' }}
      </ElDescriptionsItem>
    </ElDescriptions>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { fetchGetLogLoginDetail } from '@/api/log'
  import dayjs from 'dayjs'

  interface Props {
    modelValue: boolean
    logId: string
    logTypeOptions: { label: string; value: number }[]
  }

  interface Emits {
    (e: 'update:modelValue', value: boolean): void
  }

  const props = defineProps<Props>()
  const emit = defineEmits<Emits>()

  const visible = computed({
    get: () => props.modelValue,
    set: (value) => emit('update:modelValue', value)
  })

  const detailData = ref<Api.Log.LogLoginItem>({
    id: '',
    logUserName: '',
    logUserType: 0,
    logExecuteTime: '',
    logType: 0,
    logIp: '',
    logUserAgent: ''
  })

  watch(
    () => props.modelValue,
    async (newVal) => {
      if (newVal && props.logId) {
        await loadDetail()
      }
    }
  )

  watch(
    () => props.logId,
    async (newVal) => {
      if (newVal && props.modelValue) {
        await loadDetail()
      }
    }
  )

  const loadDetail = async () => {
    if (!props.logId) return
    try {
      const data = await fetchGetLogLoginDetail(props.logId)
      detailData.value = data
    } catch (error) {
      console.error('加载登录日志详情失败:', error)
    }
  }

  const getLogTypeLabel = (value: number) => {
    const option = props.logTypeOptions.find((o) => o.value === value)
    return option?.label ?? '-'
  }
</script>
