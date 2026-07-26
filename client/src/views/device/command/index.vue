<!-- 设备中控——指令记录（REQ-030/032/033 指令-回执-结果状态机全程可追溯） -->
<template>
  <div class="command-page art-full-height">
    <BusinessModuleNav module-key="device" />

    <!-- 搜索栏 -->
    <ArtSearchBar
      ref="searchBarRef"
      v-model="searchForm"
      :items="searchItems"
      auto-search
      @reset="handleReset"
      @search="handleSearch"
    />

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData" />

      <ArtTable
        row-key="id"
        :loading="loading"
        :columns="columns"
        :data="data"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
      </ArtTable>

      <!-- 指令详情抽屉 -->
      <ElDrawer v-model="drawerVisible" title="指令详情" size="480px">
        <ElDescriptions :column="1" border v-if="currentRow.id">
          <ElDescriptionsItem label="指令号">{{ currentRow.cmdNo }}</ElDescriptionsItem>
          <ElDescriptionsItem label="目标设备">
            {{ currentRow.deviceName }}（{{ currentRow.deviceNo }}）
          </ElDescriptionsItem>
          <ElDescriptionsItem label="指令类型">{{
            cmdTypeLabel(currentRow.cmdType!)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="指令状态">
            <ElTag :type="cmdStatusTagType(currentRow.cmdStatus!) as any">
              {{ cmdStatusLabel(currentRow.cmdStatus!) }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="关联订单">{{
            orderNoText(currentRow.cmdPayload) || currentRow.orderId || '无（运维指令）'
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="下发时间">{{
            formatTime(currentRow.sentTime)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="回执时间">{{
            formatTime(currentRow.ackTime)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="终态时间">{{
            formatTime(currentRow.finishTime)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="计划水量">{{
            planMlText(currentRow.cmdPayload)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="实际水量">{{
            actualMlText(currentRow.resultPayload)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="下发报文">
            <pre class="payload-pre">{{ prettyJson(currentRow.cmdPayload) }}</pre>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="结果报文">
            <pre class="payload-pre">{{ prettyJson(currentRow.resultPayload) }}</pre>
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="currentRow.failReason" label="失败原因">
            <span class="text-error">{{ currentRow.failReason }}</span>
          </ElDescriptionsItem>
        </ElDescriptions>
      </ElDrawer>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { h } from 'vue'
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchCommandPage, type CommandItem } from '@/api/device'
  import { ElTag } from 'element-plus'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { mlToLiter } from '@/utils/format'
  import { DictTypeEnum } from '@/constants/dict'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'

  defineOptions({ name: 'DeviceCommand' })

  const searchBarRef = ref()
  const drawerVisible = ref(false)
  const currentRow = ref<Partial<CommandItem>>({})

  const searchForm = ref({
    cmdNo: undefined as string | undefined,
    cmdStatus: undefined as number | undefined
  })

  const cmdTypeOptions = ref<{ label: string; value: number }[]>([])
  const cmdStatusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [types, statuses] = await Promise.all([
      fetchDictOptions(DictTypeEnum.指令类型),
      fetchDictOptions(DictTypeEnum.指令状态)
    ])
    cmdTypeOptions.value = toDictOptions(types)
    cmdStatusOptions.value = toDictOptions(statuses)
    getData()
  })

  const cmdTypeLabel = (v: number) =>
    cmdTypeOptions.value.find((o) => o.value === v)?.label || String(v)
  const cmdStatusLabel = (v: number) =>
    cmdStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  // 1待下发 2已下发 3已回执 4成功 5失败 6超时 7部分完成
  const cmdStatusTagType = (v: number) =>
    v === 4 ? 'success' : v === 5 || v === 6 ? 'danger' : v === 7 ? 'warning' : 'primary'

  const searchItems = computed(() => [
    {
      label: '指令号',
      key: 'cmdNo',
      type: 'input',
      placeholder: '请输入指令号',
      clearable: true
    },
    {
      label: '指令状态',
      key: 'cmdStatus',
      type: 'select',
      placeholder: '请选择状态',
      clearable: true,
      options: cmdStatusOptions.value
    }
  ])

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    resetSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchCommandPage,
      apiParams: {
        current: 1,
        size: 20,
        ...searchForm.value
      },
      // 字典未返回前先渲染骨架，避免闪烁
      immediate: false,
      columnsFactory: () => [
        { prop: 'cmdNo', label: '指令号', minWidth: 200 },
        {
          prop: 'deviceNo',
          label: '目标设备',
          minWidth: 160,
          formatter: (row: CommandItem) =>
            row.deviceName ? `${row.deviceName}（${row.deviceNo}）` : row.deviceNo || '-'
        },
        {
          prop: 'cmdType',
          label: '类型',
          minWidth: 100,
          formatter: (row: CommandItem) => cmdTypeLabel(row.cmdType)
        },
        {
          prop: 'cmdStatus',
          label: '状态',
          minWidth: 100,
          formatter: (row: CommandItem) =>
            h(ElTag, { type: cmdStatusTagType(row.cmdStatus) as any }, () =>
              cmdStatusLabel(row.cmdStatus)
            )
        },
        {
          prop: 'planMl',
          label: '计划水量',
          minWidth: 100,
          formatter: (row: CommandItem) => planMlText(row.cmdPayload)
        },
        {
          prop: 'actualMl',
          label: '实际水量',
          minWidth: 100,
          formatter: (row: CommandItem) => actualMlText(row.resultPayload)
        },
        {
          prop: 'sentTime',
          label: '下发时间',
          minWidth: 150,
          formatter: (row: CommandItem) => formatTime(row.sentTime)
        },
        {
          prop: 'ackTime',
          label: '回执时间',
          minWidth: 150,
          formatter: (row: CommandItem) => formatTime(row.ackTime)
        },
        {
          prop: 'finishTime',
          label: '终态时间',
          minWidth: 150,
          formatter: (row: CommandItem) => formatTime(row.finishTime)
        },
        {
          prop: 'failReason',
          label: '失败原因',
          minWidth: 180,
          showOverflowTooltip: true,
          formatter: (row: CommandItem) => row.failReason || '-'
        },
        {
          prop: 'operation',
          label: '操作',
          width: 90,
          fixed: 'right',
          formatter: (row: CommandItem) =>
            h(ArtButtonTable, {
              type: 'view',
              onClick: () => {
                currentRow.value = row
                drawerVisible.value = true
              }
            })
        }
      ]
    }
  })

  function formatTime(time?: string) {
    if (!time || time.length !== 14) return time || '-'
    return `${time.slice(0, 4)}-${time.slice(4, 6)}-${time.slice(6, 8)} ${time.slice(8, 10)}:${time.slice(10, 12)}:${time.slice(12, 14)}`
  }

  function prettyJson(raw?: string) {
    if (!raw) return '-'
    try {
      return JSON.stringify(JSON.parse(raw), null, 2)
    } catch {
      return raw
    }
  }

  // 指令/结果报文为后端原样透传的 JSON 字符串（CMD_PAYLOAD/RESULT_PAYLOAD），前端自解取关键字段展示，不改后端 VO
  function parsePayload(raw?: string): Record<string, any> {
    if (!raw) return {}
    try {
      const obj = JSON.parse(raw)
      return obj && typeof obj === 'object' ? obj : {}
    } catch {
      return {}
    }
  }

  const planMlText = (payload?: string) => mlToLiter(parsePayload(payload).planMl)
  const actualMlText = (payload?: string) => mlToLiter(parsePayload(payload).actualMl)
  const orderNoText = (payload?: string) => {
    const no = parsePayload(payload).orderNo
    return no ? String(no) : ''
  }

  const handleSearch = (params: Record<string, any>) => {
    replaceSearchParams(params)
    void getData()
  }

  const handleReset = () => {
    resetSearchParams()
  }

  // ==================== 非终态指令轮询（P1-06）====================
  // result 由设备端异步回写，页面需自动感知：存在非终态指令（待下发/已下发/已回执）时每 5s 刷新，
  // 全部进入终态（成功/失败/超时/部分完成）或无数据即停止。指令页在路由中 keepAlive，
  // 失活时走 onDeactivated 而非 onUnmounted，必须在此停轮询，避免切走后残留后台请求。
  const POLL_INTERVAL = 5000
  const TERMINAL_STATUS = new Set([4, 5, 6, 7])
  const hasPendingCommand = computed(() =>
    data.value.some((row: CommandItem) => !TERMINAL_STATUS.has(row.cmdStatus))
  )
  const isActive = ref(true)
  let pollTimer: ReturnType<typeof setInterval> | null = null

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  function startPolling() {
    if (pollTimer) return
    pollTimer = setInterval(() => {
      // 请求进行中或页面已失活则跳过当前周期，避免叠加请求；网络失败由 useTable 内部处理，不中断定时器
      if (!isActive.value || loading.value) return
      void getData()
    }, POLL_INTERVAL)
  }

  // 仅在页面激活且存在非终态指令时轮询；条件变化即时启停
  watch(
    [isActive, hasPendingCommand],
    ([active, pending]) => {
      if (active && pending) startPolling()
      else stopPolling()
    },
    { immediate: true }
  )

  // 列表刷新后同步已打开抽屉的数据，保证详情与列表同源
  watch(data, (rows) => {
    if (!drawerVisible.value || !currentRow.value.id) return
    const latest = (rows as CommandItem[]).find((row) => row.id === currentRow.value.id)
    if (latest) currentRow.value = latest
  })

  onActivated(() => {
    isActive.value = true
  })
  onDeactivated(() => {
    isActive.value = false
    stopPolling()
  })
  onUnmounted(stopPolling)
</script>

<style scoped lang="scss">
  .command-page {
    display: flex;
    flex-direction: column;
  }

  .payload-pre {
    max-height: 160px;
    padding: 8px;
    margin: 0;
    overflow: auto;
    font-size: 12px;
    background: var(--art-gray-100);
    border-radius: 6px;
  }
</style>
