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
    cardNo?: string
    cardType?: number
    cardStatus?: number
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

  const cardTypeOptions = ref<{ label: string; value: number }[]>([])
  const cardStatusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [types, statuses] = await Promise.all([
      fetchDictOptions(DictTypeEnum.水卡类型),
      fetchDictOptions(DictTypeEnum.水卡状态)
    ])
    cardTypeOptions.value = toDictOptions(types)
    cardStatusOptions.value = toDictOptions(statuses)
  })

  const formItems = computed(() => [
    {
      label: '卡号',
      key: 'cardNo',
      type: 'input',
      placeholder: '请输入卡号',
      clearable: true
    },
    {
      label: '卡类型',
      key: 'cardType',
      type: 'select',
      placeholder: '请选择卡类型',
      clearable: true,
      options: cardTypeOptions.value
    },
    {
      label: '卡状态',
      key: 'cardStatus',
      type: 'select',
      placeholder: '请选择卡状态',
      clearable: true,
      options: cardStatusOptions.value
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
