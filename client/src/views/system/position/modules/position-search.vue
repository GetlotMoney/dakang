<template>
  <ArtSearchBar
    ref="searchBarRef"
    v-model="formData"
    :items="formItems"
    auto-search
    :show-reset="false"
    :rules="rules"
    @reset="handleReset"
    @search="handleSearch"
  >
  </ArtSearchBar>
</template>

<script setup lang="ts">
  interface Props {
    modelValue: Api.SystemManage.PositionSearchParams
  }
  interface Emits {
    (e: 'update:modelValue', value: Api.SystemManage.PositionSearchParams): void
    (e: 'search', params: Api.SystemManage.PositionSearchParams): void
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

  const formItems = computed(() => [
    {
      label: '职务名称',
      key: 'positionName',
      type: 'input',
      placeholder: '请输入职务名称',
      clearable: true,
      maxlength: 20
    }
  ])

  function handleReset() {
    emit('reset')
  }

  async function handleSearch(params: Api.SystemManage.PositionSearchParams) {
    await searchBarRef.value.validate()
    emit('search', params)
  }
</script>
