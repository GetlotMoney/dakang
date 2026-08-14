<!-- 操作日志详情：只回答「谁、何时、对哪个模块、做了什么、结果如何」。
     请求地址/请求方法/请求报文/响应报文/UA/监控信息一律不再直出——报文即使已脱敏，
     摆在页面上也只是把内部实现讲给运营看，而失败分支的历史数据里还留着整段 Java 堆栈。 -->
<template>
  <ElDrawer v-model="visible" title="操作日志详情" direction="rtl" size="600px">
    <ElDescriptions :column="1" border label-width="140px">
      <ElDescriptionsItem label="管理员">
        {{ detailData.logUserName || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="操作模块">
        {{ detailData.logModule || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="动作类型">
        {{ detailData.logContent || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="操作时间">
        {{
          detailData.logExecuteTime
            ? dayjs(detailData.logExecuteTime, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss')
            : '-'
        }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="执行结果">
        {{ getLogSuccessFlagLabel(detailData.logSuccessFlag) }}
      </ElDescriptionsItem>
      <ElDescriptionsItem v-if="failureReason" label="失败原因">
        <span style="word-break: break-all">{{ failureReason }}</span>
      </ElDescriptionsItem>
      <ElDescriptionsItem label="耗时">
        {{ detailData.logConsumerTime != null ? detailData.logConsumerTime + ' ms' : '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="操作IP">
        {{ detailData.logIp || '-' }}
      </ElDescriptionsItem>
    </ElDescriptions>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { fetchGetLogOperationDetail } from '@/api/log'
  import { ApiStatus } from '@/utils/http/status'
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

  /**
   * 失败原因：只取结果摘要里的那句结论，也就是当时回给操作者的原话。
   *
   * 解析不出结构就什么都不显示——早期失败记录里存的是整段 Java 堆栈，
   * 一旦回落成"原样打印"，这个抽屉就又成了堆栈的出口。
   */
  const failureReason = computed(() => {
    const raw = detailData.value.logResponseParam
    if (!raw) return ''
    try {
      const parsed = JSON.parse(raw)
      if (parsed?.code === ApiStatus.success) return ''
      const msg = parsed?.msg
      return typeof msg === 'string' ? msg.slice(0, 200) : ''
    } catch {
      return ''
    }
  })
</script>

<style scoped></style>
