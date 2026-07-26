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
    courierName?: string
    courierPhone?: string
    courierStatus?: number
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

  const statusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    statusOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.配送员状态))
  })

  const formItems = computed(() => [
    {
      label: '姓名',
      key: 'courierName',
      type: 'input',
      placeholder: '请输入配送员姓名',
      clearable: true
    },
    {
      label: '联系电话',
      key: 'courierPhone',
      type: 'input',
      placeholder: '请输入联系电话',
      clearable: true
    },
    {
      label: '准入状态',
      key: 'courierStatus',
      type: 'select',
      placeholder: '请选择准入状态',
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
