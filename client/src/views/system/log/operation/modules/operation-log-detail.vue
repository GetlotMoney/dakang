<template>
  <ElDrawer v-model="visible" title="操作日志详情" direction="rtl" size="600px">
    <ElDescriptions :column="1" border label-width="140px">
      <ElDescriptionsItem label="ID">
        {{ detailData.id || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="用户名">
        {{ detailData.logUserName || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="操作模块">
        {{ detailData.logModule || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="操作内容">
        {{ detailData.logContent || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="请求地址">
        {{ detailData.logUrl || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="请求方法">
        {{ detailData.logMethod || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="请求参数" :span="1">
        <template v-if="detailData.logRequestParam">
          <pre style="margin: 0; white-space: pre-wrap; word-break: break-all">{{
            formatJson(detailData.logRequestParam)
          }}</pre>
        </template>
        <span v-else>-</span>
      </ElDescriptionsItem>
      <ElDescriptionsItem label="响应参数" :span="1">
        <template v-if="detailData.logResponseParam">
          <pre style="margin: 0; white-space: pre-wrap; word-break: break-all">{{
            formatJson(detailData.logResponseParam)
          }}</pre>
        </template>
        <span v-else>-</span>
      </ElDescriptionsItem>
      <ElDescriptionsItem label="操作时间">
        {{
          detailData.logExecuteTime
            ? dayjs(detailData.logExecuteTime, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss')
            : '-'
        }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="消耗时间">
        {{ detailData.logConsumerTime != null ? detailData.logConsumerTime + ' ms' : '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="操作IP">
        {{ detailData.logIp || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="User-Agent">
        <span style="word-break: break-all">{{ detailData.logUserAgent || '-' }}</span>
      </ElDescriptionsItem>
      <ElDescriptionsItem label="执行结果">
        {{ getLogSuccessFlagLabel(detailData.logSuccessFlag) }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="监控信息">
        {{ detailData.logMonitorInfo || '-' }}
      </ElDescriptionsItem>
    </ElDescriptions>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { fetchGetLogOperationDetail } from '@/api/log'
  import dayjs from 'dayjs'

  interface Props {
    modelValue: boolean
    logId: string
    logSuccessFlagOptions: { label: string; value: number }[]
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

  const detailData = ref<Api.Log.LogOperationItem>({
    id: '',
    logUserName: '',
    logUserType: 0,
    logModule: '',
    logContent: '',
    logUrl: '',
    logMethod: '',
    logRequestParam: '',
    logResponseParam: '',
    logIp: '',
    logUserAgent: '',
    logExecuteTime: '',
    logConsumerTime: 0,
    logMonitorInfo: '',
    logSuccessFlag: 0
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
      const data = await fetchGetLogOperationDetail(props.logId)
      detailData.value = data
    } catch (error) {
      console.error('加载操作日志详情失败:', error)
    }
  }

  const getLogSuccessFlagLabel = (value: number) => {
    const option = props.logSuccessFlagOptions.find((o) => o.value === value)
    return option?.label ?? '-'
  }

  const formatJson = (str: string) => {
    try {
      return JSON.stringify(JSON.parse(str), null, 2)
    } catch {
      return str
    }
  }
</script>

<style scoped></style>
