<template>
  <ArtSearchBar
    ref="searchBarRef"
    v-model="formData"
    :items="formItems"
    auto-search
    :rules="rules"
    @reset="handleReset"
    @search="handleSearch"
  >
  </ArtSearchBar>
</template>

<script setup lang="ts">
  type LogOperationSearchParams = Api.Log.LogOperationSearchParams & {
    daterange?: string[]
  }

  interface Props {
    modelValue: LogOperationSearchParams
    logSuccessFlagOptions: { label: string; value: number }[]
  }

  interface Emits {
    (e: 'update:modelValue', value: LogOperationSearchParams): void
    (e: 'search', params: LogOperationSearchParams): void
    (e: 'reset'): void
  }

  const props = defineProps<Props>()
  const emit = defineEmits<Emits>()

  const searchBarRef = ref()

  const formData = computed({
    get: () => props.modelValue,
    set: (val) => emit('update:modelValue', val)
  })

  const rules = {}

  // 前三项默认展开、其余进「更多筛选」：审计追人先按「谁、做了什么、什么时候」定位，
  // 模块与执行结果是缩小范围用的第二层条件
  const formItems = computed(() => [
    {
      label: '管理员',
      key: 'logUserName',
      type: 'input',
      placeholder: '请输入管理员姓名',
      clearable: true
    },
    {
      label: '动作类型',
      key: 'logContent',
      type: 'input',
      placeholder: '如：审核、退款、下发',
      clearable: true
    },
    {
      label: '操作时间',
      key: 'daterange',
      type: 'datetime',
      props: {
        style: { width: '100%' },
        placeholder: '请选择操作时间范围',
        type: 'daterange',
        rangeSeparator: '至',
        startPlaceholder: '开始日期',
        endPlaceholder: '结束日期',
        valueFormat: 'YYYY-MM-DD',
        shortcuts: [
          { text: '今日', value: [new Date(), new Date()] },
          { text: '最近一周', value: [new Date(Date.now() - 604800000), new Date()] },
          { text: '最近一个月', value: [new Date(Date.now() - 2592000000), new Date()] }
        ]
      }
    },
    {
      label: '操作模块',
      key: 'logModule',
      type: 'input',
      placeholder: '请输入操作模块',
      clearable: true
    },
    {
      label: '执行结果',
      key: 'logSuccessFlag',
      type: 'select',
      placeholder: '请选择执行结果',
      clearable: true,
      props: {
        options: props.logSuccessFlagOptions
      }
    }
  ])

  const handleReset = () => {
    emit('reset')
  }

  const handleSearch = async (params: LogOperationSearchParams) => {
    await searchBarRef.value.validate()
    emit('search', params)
  }
</script>
