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
  import { fetchStationList } from '@/api/station'

  interface SearchForm {
    deviceNo?: string
    deviceName?: string
    stationId?: number
    onlineStatus?: number
    runStatus?: number
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

  const onlineOptions = ref<{ label: string; value: number }[]>([])
  const runOptions = ref<{ label: string; value: number }[]>([])
  const stationOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [online, run, stations] = await Promise.all([
      fetchDictOptions(DictTypeEnum.设备在线状态),
      fetchDictOptions(DictTypeEnum.设备运行状态),
      fetchStationList()
    ])
    onlineOptions.value = toDictOptions(online)
    runOptions.value = toDictOptions(run)
    stationOptions.value = stations.map((s) => ({ label: s.stationName, value: s.id }))
  })

  const formItems = computed(() => [
    {
      label: '设备编号',
      key: 'deviceNo',
      type: 'input',
      placeholder: '请输入设备编号',
      clearable: true
    },
    {
      label: '设备名称',
      key: 'deviceName',
      type: 'input',
      placeholder: '请输入设备名称',
      clearable: true
    },
    {
      label: '所属水站',
      key: 'stationId',
      type: 'select',
      placeholder: '请选择水站',
      clearable: true,
      options: stationOptions.value
    },
    {
      label: '在线状态',
      key: 'onlineStatus',
      type: 'select',
      placeholder: '请选择在线状态',
      clearable: true,
      options: onlineOptions.value
    },
    {
      label: '运行状态',
      key: 'runStatus',
      type: 'select',
      placeholder: '请选择运行状态',
      clearable: true,
      options: runOptions.value
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
