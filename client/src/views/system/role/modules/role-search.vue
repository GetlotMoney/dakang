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
  type RoleSearchFormParams = Api.SystemManage.RoleSearchParams

  interface Props {
    modelValue: RoleSearchFormParams
  }

  interface Emits {
    (e: 'update:modelValue', value: RoleSearchFormParams): void
    (e: 'search', params: RoleSearchFormParams): void
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
      label: '角色名称',
      key: 'roleName',
      type: 'input',
      placeholder: '请输入角色名称',
      clearable: true
    },
    {
      label: '角色编码',
      key: 'roleCode',
      type: 'input',
      placeholder: '请输入角色编码',
      clearable: true
    }
  ])

  const handleReset = () => {
    emit('reset')
  }

  const handleSearch = async (params: RoleSearchFormParams) => {
    await searchBarRef.value.validate()
    emit('search', params)
  }
</script>
