<template>
  <ArtSearchBar
    ref="searchBarRef"
    v-model="formData"
    :items="formItems"
    auto-search
    @reset="handleReset"
    @search="handleSearch"
  >
  </ArtSearchBar>
</template>

<script setup lang="ts">
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'

  interface SearchForm {
    stationName?: string
    stationCode?: string
    stationRegion?: string
    stationStatus?: number
  }

  interface Emits {
    (e: 'update:modelValue', value: SearchForm): void
    (e: 'search', params: SearchForm): void
    (e: 'reset'): void
  }

  const props = defineProps<{ modelValue: SearchForm }>()
  const emit = defineEmits<Emits>()

  const searchBarRef = ref()
  const formData = computed({
    get: () => props.modelValue,
    set: (val) => emit('update:modelValue', val)
  })

  // 状态选项（字典 10：1正常 2禁用）
  const statusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    statusOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.禁用状态))
  })

  const formItems = computed(() => [
    {
      label: '水站名称',
      key: 'stationName',
      type: 'input',
      placeholder: '请输入水站名称',
      clearable: true
    },
    {
      label: '水站编码',
      key: 'stationCode',
      type: 'input',
      placeholder: '请输入水站编码',
      clearable: true
    },
    {
      label: '所属区域',
      key: 'stationRegion',
      type: 'input',
      placeholder: '请输入所属区域',
      clearable: true
    },
    {
      label: '状态',
      key: 'stationStatus',
      type: 'select',
      placeholder: '请选择状态',
      clearable: true,
      options: statusOptions.value
    }
  ])

  function handleReset() {
    emit('reset')
  }

  async function handleSearch(params: SearchForm) {
    await searchBarRef.value.validate()
    emit('search', params)
  }
</script>
