<!-- 站内消息记录（E2E-07 包D / REQ-087「后台记录」）：全域只读查询，无任何写入口 -->
<template>
  <div class="art-full-height">
    <BusinessModuleNav module-key="user" />

    <!-- 全仓统一搜索形态：auto-search 任一字段变更即防抖查询，无独立查询按钮 -->
    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      auto-search
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData" />

      <ArtTable
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
      </ArtTable>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchMessageRecordPage } from '@/api/message'
  import { fetchDictByTypes } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { ElMessage, ElTag } from 'element-plus'
  import dayjs from 'dayjs'

  defineOptions({ name: 'WsUserMessage' })

  type MessageRecordItem = Api.Message.MessageRecordItem

  const searchForm = ref<{ userId?: string; msgDomain?: number; sendStatus?: number }>({})

  const searchItems = computed(() => [
    {
      label: '收件用户ID',
      key: 'userId',
      type: 'input',
      placeholder: '收件用户ID',
      clearable: true
    },
    {
      label: '消息领域',
      key: 'msgDomain',
      type: 'select',
      placeholder: '消息领域',
      clearable: true,
      options: domainOptions.value
    },
    {
      label: '发送状态',
      key: 'sendStatus',
      type: 'select',
      placeholder: '发送状态',
      clearable: true,
      options: sendStatusOptions.value
    }
  ])

  const domainOptions = ref<{ label: string; value: number }[]>([])
  const sendStatusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [domainDict, statusDict] = await fetchDictByTypes([
      DictTypeEnum.消息领域,
      DictTypeEnum.消息发送状态
    ])
    domainOptions.value = domainDict.dictDataList.map((d) => ({
      label: d.dictLabel,
      value: Number(d.dictValue)
    }))
    sendStatusOptions.value = statusDict.dictDataList.map((d) => ({
      label: d.dictLabel,
      value: Number(d.dictValue)
    }))
  })

  const dictLabel = (options: { label: string; value: number }[], value: number) =>
    options.find((o) => o.value === value)?.label ?? String(value)

  const formatBizTime = (time?: string) =>
    time ? dayjs(time, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss') : '—'

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchMessageRecordPage,
      apiParams: {
        current: 1,
        size: 20
      },
      columnsFactory: () => [
        { prop: 'id', label: 'ID', width: 80 },
        { prop: 'userId', label: '收件用户', width: 100 },
        {
          prop: 'msgDomain',
          label: '领域',
          width: 90,
          formatter: (row: MessageRecordItem) => dictLabel(domainOptions.value, row.msgDomain)
        },
        { prop: 'msgTitle', label: '标题', minWidth: 160 },
        { prop: 'msgContent', label: '正文', minWidth: 240, showOverflowTooltip: true },
        {
          prop: 'sendStatus',
          label: '发送状态',
          width: 110,
          formatter: (row: MessageRecordItem) =>
            h(
              ElTag,
              // 发送失败标红——本页存在的意义之一就是让失败态无处遁形（REQ-087 重试/降级观察面）
              {
                type: row.sendStatus === 3 ? 'danger' : row.sendStatus === 4 ? 'success' : 'warning'
              },
              () => dictLabel(sendStatusOptions.value, row.sendStatus)
            )
        },
        {
          prop: 'msgChannel',
          label: '渠道',
          width: 90,
          formatter: (row: MessageRecordItem) =>
            row.msgChannel === 1 ? '站内' : `外部(${row.msgChannel})`
        },
        {
          prop: 'readFlag',
          label: '已读',
          width: 80,
          formatter: (row: MessageRecordItem) => (row.readFlag === 1 ? '已读' : '未读')
        },
        {
          prop: 'sendTime',
          label: '发送时间',
          width: 170,
          formatter: (row: MessageRecordItem) => formatBizTime(row.sendTime)
        },
        {
          prop: 'objectId',
          label: '关联对象',
          minWidth: 140,
          formatter: (row: MessageRecordItem) =>
            row.objectType ? `${row.objectType}:${row.objectId ?? '—'}` : '—'
        }
      ]
    }
  })

  const handleSearch = () => {
    // userId 是自由文本输入：非数字直接前端拒绝，不把反序列化错误留给后端
    const raw = String(searchForm.value.userId ?? '').trim()
    if (raw && !/^\d+$/.test(raw)) {
      ElMessage.warning('收件用户ID必须是数字')
      return
    }
    replaceSearchParams({ ...searchForm.value, userId: raw || undefined })
    getData()
  }

  const handleReset = () => {
    searchForm.value = {}
    replaceSearchParams({})
    getData()
  }
</script>
