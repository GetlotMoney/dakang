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
    modelValue: { tagName?: string }
  }
  interface Emits {
    (e: 'update:modelValue', value: { tagName?: string }): void
    (e: 'search', params: { tagName?: string }): void
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
      label: '标签名称',
      key: 'tagName',
      type: 'input',
      placeholder: '请输入标签名称',
      clearable: true
    }
  ])

  function handleReset() {
    emit('reset')
  }

  async function handleSearch(params: { tagName?: string }) {
    await searchBarRef.value.validate()
    emit('search', params)
  }
</script>
