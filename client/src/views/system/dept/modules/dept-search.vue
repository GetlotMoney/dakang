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
  interface Props {
    modelValue: { deptName?: string }
  }
  interface Emits {
    (e: 'update:modelValue', value: { deptName?: string }): void
    (e: 'search', params: { deptName?: string }): void
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
      label: '部门名称',
      key: 'deptName',
      type: 'input',
      placeholder: '请输入部门名称',
      clearable: true
    }
  ])

  function handleReset() {
    emit('reset')
  }

  async function handleSearch(params: { deptName?: string }) {
    await searchBarRef.value.validate()
    emit('search', params)
  }
</script>
